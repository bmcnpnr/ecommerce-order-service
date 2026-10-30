package com.ecommerce.order.config;

import com.ecommerce.order.kafka.DeadLetterRecoverer;
import com.ecommerce.order.kafka.ListenerFailureLogger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaErrorHandlingTest {

    private static ListenerExecutionFailedException listenerFailed(Throwable cause) {
        return new ListenerExecutionFailedException("Listener method could not be invoked", cause);
    }

    static Stream<Throwable> infrastructureFailures() {
        return Stream.of(
                new CannotGetJdbcConnectionException("pool exhausted"),
                new CannotCreateTransactionException("could not open"),
                new DataAccessResourceFailureException("connection reset"),
                new QueryTimeoutException("statement timeout"),
                new PessimisticLockingFailureException("lock timeout"),
                new RecoverableDataAccessException("recoverable"),
                new ObjectOptimisticLockingFailureException(Object.class, 1L),   // retried: the guard re-reads the order
                new TimeoutException("broker did not answer"),
                // a backend killed mid-transaction: the rollback fails too and replaces the original error
                new org.springframework.transaction.TransactionSystemException("Could not roll back JPA transaction",
                        new jakarta.persistence.PersistenceException("Unable to rollback against JDBC Connection",
                                new java.sql.SQLException("This connection has been closed.", "08003"))),
                new org.hibernate.TransactionException("JDBC commit failed", new java.net.SocketException("Connection reset")));
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void infrastructureTrouble_isRetriedForeverWithAGrowingPause_evenWrappedByTheListener(Throwable cause) {
        BackOff backOff = KafkaErrorHandling.backOffFor(listenerFailed(cause));

        assertThat(backOff).isInstanceOf(ExponentialBackOff.class);
        ExponentialBackOff exponential = (ExponentialBackOff) backOff;
        assertThat(exponential.getInitialInterval()).isEqualTo(1000);
        assertThat(exponential.getMultiplier()).isEqualTo(2.0);
        assertThat(exponential.getMaxInterval()).isEqualTo(30_000);
        assertThat(exponential.getMaxElapsedTime()).as("no limit: the partition waits for the database").isEqualTo(Long.MAX_VALUE);
        BackOffExecution execution = backOff.start();
        List<Long> pauses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pauses.add(execution.nextBackOff());
        }
        assertThat(pauses).containsExactly(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L);
    }

    @Test
    void anythingElse_getsTwoRetriesOneSecondApart_thenTheDeadLetterTopic() {
        BackOff backOff = KafkaErrorHandling.backOffFor(listenerFailed(new IllegalStateException("bug")));

        assertThat(backOff).isInstanceOf(FixedBackOff.class);
        BackOffExecution execution = backOff.start();
        assertThat(execution.nextBackOff()).isEqualTo(1000);
        assertThat(execution.nextBackOff()).isEqualTo(1000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void aTransactionFailureWithoutAConnectionUnderneath_isNotInfrastructure_soItDoesNotBlockThePartitionForever() {
        assertThat(KafkaErrorHandling.isTransient(listenerFailed(new org.springframework.transaction.TransactionSystemException(
                "Could not commit JPA transaction", new IllegalStateException("bug"))))).isFalse();
        assertThat(KafkaErrorHandling.isTransient(listenerFailed(new org.springframework.transaction.TransactionSystemException(
                "Could not roll back JPA transaction", new java.sql.SQLException("duplicate key", "23505"))))).isFalse();
    }

    @Test
    void aConversionFailure_isNotInfrastructure() {
        assertThat(KafkaErrorHandling.isTransient(listenerFailed(new ConversionException("not json", new RuntimeException())))).isFalse();
    }

    // ---- the wiring: one recoverer and one failure logger, the same as in shipping- and notification-service ----

    @Test
    @SuppressWarnings("unchecked")
    void aFailedRecord_goesToTheTopicDlt_unchanged_withAnyPartition_andIsCounted() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meters);
        DeadLetterRecoverer recoverer = new KafkaProducerConfig().deadLetterRecoverer(template, provider);

        recoverer.accept(new ConsumerRecord<>("payment.failed", 0, 17L, "42", "not json at all"), null,
                listenerFailed(new ConversionException("not json", new RuntimeException())));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("payment.failed.DLT");
        assertThat(sent.getValue().partition()).as("the producer picks one").isNull();
        assertThat(sent.getValue().key()).isEqualTo("42");
        assertThat(sent.getValue().value()).isEqualTo("not json at all");
        assertThat(meters.get("kafka.dlt.published").tag("topic", "payment.failed").counter().count()).isEqualTo(1.0);
        recoverer.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRecovererWorksWithoutAMeterRegistry() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
        ObjectProvider<MeterRegistry> none = mock(ObjectProvider.class);
        DeadLetterRecoverer recoverer = new KafkaProducerConfig().deadLetterRecoverer(template, none);

        recoverer.accept(new ConsumerRecord<>("payment.completed", 0, 1L, "1", "x"), null, listenerFailed(new IllegalStateException("bug")));

        verify(template).send(any(ProducerRecord.class));
        recoverer.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyFailedDeliveryIsReported_throughTheThrottledFailureLogger() {
        DeadLetterRecoverer recoverer = new DeadLetterRecoverer(mock(KafkaTemplate.class),
                (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", -1), null);

        DefaultErrorHandler handler = KafkaErrorHandling.errorHandlerFor(recoverer);

        List<?> listeners = ReflectionTestUtils.invokeMethod(handler, "getRetryListeners");
        assertThat(listeners).hasSize(1).first().isInstanceOf(ListenerFailureLogger.class);
        recoverer.close();
    }

    /** The relay and the recoverer report a failed send themselves; the default listener would add an ERROR with a stack trace. */
    @Test
    void theSharedTemplateDoesNotLogEveryFailedSendWithAStackTrace() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");

        KafkaTemplate<String, String> template = config.kafkaTemplate();

        assertThat(ReflectionTestUtils.getField(template, "producerListener")).isNotInstanceOf(LoggingProducerListener.class);
    }
}

package com.ecommerce.order.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.logging.ThrottleFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxEventRepository repository;
    @Mock private KafkaTemplate<String, String> kafka;
    @Mock private PlatformTransactionManager transactionManager;

    private final AtomicLong clock = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OutboxRelay relay;

    private ListAppender<ILoggingEvent> logs;
    private Logger relayLogger;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, kafka, transactionManager, meters, 100, 1000, clock::get,
                ThrottleFixtures.withClock(60, TimeUnit.SECONDS, clock::get));
        lenient().when(repository.countByParkedAtIsNull()).thenReturn(0L);
        relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        logs = new ListAppender<>();
        logs.start();
        relayLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        relayLogger.detachAppender(logs);
    }

    private void advanceMillis(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static OutboxEvent row(long id, String topic) {
        return OutboxEvent.builder().id(id).topic(topic).eventKey("k" + id).payload("{\"id\":" + id + "}").build();
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed(Throwable t) {
        return CompletableFuture.failedFuture(t);
    }

    private List<ILoggingEvent> atLeast(Level level) {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(level)).toList();
    }

    // ---- the happy path ----

    @Test
    void publishesRowsOldestFirst_andDeletesEachOnceKafkaAcknowledged() {
        OutboxEvent first = row(1, "order.placed");
        OutboxEvent second = row(2, "order.cancelled");
        when(repository.lockBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(anyTopic(), any(), any())).thenReturn(acknowledged());

        relay.publishPending();

        InOrder inOrder = inOrder(kafka, repository);
        inOrder.verify(kafka).send("order.placed", "k1", "{\"id\":1}");
        inOrder.verify(repository).delete(first);
        inOrder.verify(kafka).send("order.cancelled", "k2", "{\"id\":2}");
        inOrder.verify(repository).delete(second);
        assertThat(meters.get("outbox.events.published").counter().count()).isEqualTo(2.0);
        assertThat(atLeast(Level.INFO)).isEmpty();
    }

    @Test
    void anEmptyOutbox_costsOneQueryAndNothingElse() {
        when(repository.lockBatch(100)).thenReturn(List.of());

        relay.publishPending();

        verify(kafka, never()).send(anyTopic(), any(), any());
        assertThat(meters.get("outbox.events.published").counter().count()).isZero();
    }

    // ---- transient failures: keep order, back off, stay quiet ----

    @Test
    void aTransientFailure_stopsTheBatch_keepsTheRow_andRecordsWhyOnIt() {
        OutboxEvent sent = row(1, "order.placed");
        OutboxEvent broken = row(2, "order.placed");
        OutboxEvent behind = row(3, "order.placed");
        when(repository.lockBatch(100)).thenReturn(List.of(sent, broken, behind));
        when(kafka.send(anyTopic(), eq("k1"), any())).thenReturn(acknowledged());
        when(kafka.send(anyTopic(), eq("k2"), any())).thenReturn(failed(new TimeoutException("Topic order.placed not present in metadata after 5000 ms.")));

        relay.publishPending();

        verify(repository).delete(sent);
        verify(repository, never()).delete(broken);
        verify(kafka, never()).send(anyTopic(), eq("k3"), any()); // ordering: nothing overtakes the failed row
        assertThat(broken.getAttempts()).isEqualTo(1);
        assertThat(broken.getLastError()).startsWith("TimeoutException: Topic order.placed not present in metadata");
        assertThat(broken.getParkedAt()).isNull();
        assertThat(meters.get("outbox.publish.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void afterAFailure_theRelayStaysAwayFromTheBrokerAndTheDatabaseUntilTheBackoffEnds() {
        OutboxEvent broken = row(1, "order.placed");
        when(repository.lockBatch(100)).thenReturn(List.of(broken));
        when(kafka.send(anyTopic(), any(), any())).thenReturn(failed(new TimeoutException("broker down")));

        relay.publishPending();                 // fails, pauses 1 s
        advanceMillis(500);
        relay.publishPending();                 // still paused
        relay.publishPending();                 // still paused
        verify(repository, times(1)).lockBatch(anyInt());

        advanceMillis(600);
        relay.publishPending();                 // 1.1 s later: tries again, fails, pauses 2 s
        verify(repository, times(2)).lockBatch(anyInt());
        advanceMillis(1500);
        relay.publishPending();
        verify(repository, times(2)).lockBatch(anyInt());
        advanceMillis(600);
        relay.publishPending();
        verify(repository, times(3)).lockBatch(anyInt());
    }

    @Test
    void backoffGrowsFromOneSecondAndStopsAtThirty() {
        assertThat(List.of(1, 2, 3, 4, 5, 6, 7, 30, 1000)).extracting(OutboxRelay::backoffMillis)
                .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L);
    }

    /** A broker that stays down for ten minutes costs a handful of lines, not one per attempt. */
    @Test
    void aLongOutage_logsOneWarnPerMinute_withoutStackTraces_thenOneInfoWhenItIsOver() {
        OutboxEvent broken = row(1, "order.placed");
        when(repository.lockBatch(100)).thenReturn(List.of(broken));
        when(kafka.send(anyTopic(), any(), any())).thenReturn(failed(new TimeoutException("broker down")));

        int cycles = 0;
        for (int second = 0; second < 600; second++) {      // ten minutes, one relay tick per second
            relay.publishPending();
            advanceMillis(1000);
            cycles++;
        }
        List<ILoggingEvent> warnings = atLeast(Level.WARN);

        assertThat(cycles).isEqualTo(600);
        assertThat(warnings).hasSizeBetween(9, 12);                       // ~1 per minute, never one per attempt
        assertThat(warnings).allSatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getThrowableProxy()).as("no stack trace for a dead broker").isNull();
        });
        assertThat(warnings.get(0).getFormattedMessage()).contains("order.placed").contains("TimeoutException").contains("retrying in");

        // Kafka comes back
        when(kafka.send(anyTopic(), any(), any())).thenReturn(acknowledged());
        advanceMillis(60_000);
        relay.publishPending();

        verify(repository).delete(broken);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.INFO).hasSize(1)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("resumed after").contains("failed attempts"));
        // and the next failure after a success is announced again
        when(repository.lockBatch(100)).thenReturn(List.of(row(2, "order.placed")));
        when(kafka.send(anyTopic(), any(), any())).thenReturn(failed(new TimeoutException("down again")));
        int before = atLeast(Level.WARN).size();
        relay.publishPending();
        assertThat(atLeast(Level.WARN)).hasSize(before + 1);
    }

    @Test
    void whenTheDatabaseFails_thereIsNoExceptionAndTheRelayBacksOff() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        relay.publishPending();
        relay.publishPending();   // paused

        verify(transactionManager, times(1)).getTransaction(any());
        assertThat(atLeast(Level.WARN)).hasSize(1).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
        assertThat(meters.get("outbox.publish.failures").counter().count()).isEqualTo(1.0);
    }

    // ---- permanent failures: park the row, carry on ----

    static Stream<Throwable> permanentFailures() {
        return Stream.of(
                new RecordTooLargeException("The message is 2097152 bytes when serialized which is larger than 1048576"),
                new InvalidTopicException("bad topic!"),
                new SerializationException("cannot serialize"),
                new RuntimeException("wrapper", new RecordTooLargeException("too large")));
    }

    @ParameterizedTest
    @MethodSource("permanentFailures")
    void aPermanentFailure_parksThatRowOnce_andTheNextRowIsStillSent(Throwable failure) {
        OutboxEvent poison = row(1, "order.placed");
        OutboxEvent healthy = row(2, "order.placed");
        when(repository.lockBatch(100)).thenReturn(List.of(poison, healthy));
        when(kafka.send(anyTopic(), eq("k1"), any())).thenReturn(failed(failure));
        when(kafka.send(anyTopic(), eq("k2"), any())).thenReturn(acknowledged());

        relay.publishPending();

        assertThat(poison.getParkedAt()).isNotNull();
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).isNotBlank();
        verify(repository, never()).delete(poison);
        verify(repository).delete(healthy);
        assertThat(atLeast(Level.WARN)).hasSize(1).allSatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("Outbox event 1").contains("UPDATE outbox_event SET parked_at = NULL WHERE id = 1");
        });
        // a parked row is a settled matter, not an outage: no back-off
        relay.publishPending();
        verify(repository, times(2)).lockBatch(100);
    }

    @Test
    void anErrorMessageLongerThanTheColumn_isCapped() {
        OutboxEvent broken = row(1, "order.placed");
        when(repository.lockBatch(100)).thenReturn(List.of(broken));
        when(kafka.send(anyTopic(), any(), any())).thenReturn(failed(new TimeoutException("x".repeat(5000))));

        relay.publishPending();

        assertThat(broken.getLastError()).hasSize(500);
    }

    private static String anyTopic() {
        return any(String.class);
    }
}

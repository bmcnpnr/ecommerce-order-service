package com.ecommerce.order.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens when a record is dead-lettered, and above all when dead-lettering itself fails: the
 * record must not come back at poll rate with a stack trace per round (the 2026-08-15 incident).
 */
class DeadLetterRecovererTest {

    @SuppressWarnings("unchecked")
    private final KafkaOperations<Object, Object> bytesTemplate = mock(KafkaOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Long> pauses = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DeadLetterRecoverer.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private DeadLetterRecoverer recoverer;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        recoverer = newRecoverer();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    private DeadLetterRecoverer newRecoverer() {
        return new DeadLetterRecoverer(bytesTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1),
                meters, millis -> {
                    pauses.add(millis);
                    return true;
                });
    }

    private static ConsumerRecord<String, String> record(long offset) {
        return new ConsumerRecord<>("payment.completed", 0, offset, "7", "{not json");
    }

    /** What the String-valued listener's converter throws for a payload that is not JSON for the event class. */
    private static Exception unreadable(String message) {
        return new org.springframework.kafka.listener.ListenerExecutionFailedException("Listener method could not be invoked",
                new org.springframework.messaging.converter.MessageConversionException(message,
                        new IllegalArgumentException(message)));
    }

    private static CompletableFuture<SendResult<Object, Object>> acknowledged() {
        return CompletableFuture.completedFuture(new SendResult<>(null, null));
    }

    private static CompletableFuture<SendResult<Object, Object>> failed(Throwable cause) {
        return CompletableFuture.failedFuture(cause);
    }

    private List<ILoggingEvent> lines(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    private double counter(String name) {
        var counter = meters.find(name).tag("topic", "payment.completed").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aReadableFailureIsDeadLettered_withOneWarnLine_andNoStackTrace() {
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        recoverer.accept(record(5), null, unreadable("Unrecognized token 'ABC'"));

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(bytesTemplate).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("payment.completed.DLT");
        assertThat(sent.getValue().partition()).isNull();
        // the original key and value travel unchanged
        assertThat(sent.getValue().key()).isEqualTo("7");
        assertThat(sent.getValue().value()).isEqualTo("{not json");
        // the stack trace header is what pushes a near-limit record over max.request.size
        assertThat(sent.getValue().headers().lastHeader("kafka_dlt-exception-stacktrace")).isNull();
        assertThat(sent.getValue().headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        assertThat(sent.getValue().headers().lastHeader("kafka_dlt-original-topic")).isNotNull();

        assertThat(counter("kafka.dlt.published")).isEqualTo(1);
        assertThat(lines(Level.WARN)).hasSize(1);
        ILoggingEvent line = lines(Level.WARN).get(0);
        assertThat(line.getThrowableProxy()).isNull();
        assertThat(line.getFormattedMessage()).contains("payment.completed-0@5").contains("Unrecognized token 'ABC'");
        assertThat(lines(Level.ERROR)).isEmpty();
    }

    @Test
    void aFloodOfPoisonRecords_logsOncePerWindow_butCountsEveryOne() {
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        for (int i = 0; i < 500; i++) {
            recoverer.accept(record(i), null, unreadable("bad " + i));
        }

        assertThat(counter("kafka.dlt.published")).isEqualTo(500);
        assertThat(lines(Level.WARN)).hasSize(1);
    }

    @Test
    void aRecordThatNoLongerFitsWithItsExceptionHeaders_isRetriedBare_andStillDeadLettered() {
        when(bytesTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new RecordTooLargeException("1048600 bytes is larger than max.request.size")))
                .thenReturn(acknowledged());

        recoverer.accept(record(5), null, unreadable("bad"));

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(bytesTemplate, times(2)).send(sent.capture());
        assertThat(sent.getAllValues().get(0).headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        ProducerRecord<Object, Object> bare = sent.getAllValues().get(1);
        assertThat(bare.headers().lastHeader("kafka_dlt-exception-fqcn")).isNull();
        assertThat(bare.headers().lastHeader("kafka_dlt-exception-message")).isNull();
        assertThat(bare.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
        assertThat(bare.headers().lastHeader("kafka_dlt-original-offset")).isNotNull();
        assertThat(bare.value()).isEqualTo("{not json");
        assertThat(counter("kafka.dlt.published")).isEqualTo(1);
        assertThat(counter("kafka.dlt.dropped")).isZero();
        assertThat(lines(Level.ERROR)).isEmpty();
        assertThat(pauses).isEmpty();
    }

    @Test
    void aTooLargeRecord_staysBare_whenTheBrokerHiccupsOnTheRetry() {
        when(bytesTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new RecordTooLargeException("1048600 bytes is larger than max.request.size")))
                .thenReturn(failed(new TimeoutException("metadata")))
                .thenReturn(acknowledged());

        recoverer.accept(record(5), null, unreadable("bad"));

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(bytesTemplate, times(3)).send(sent.capture());
        assertThat(sent.getAllValues().get(0).headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        // the retries after the size problem do not bring the exception headers back
        assertThat(sent.getAllValues().get(1).headers().lastHeader("kafka_dlt-exception-fqcn")).isNull();
        assertThat(sent.getAllValues().get(2).headers().lastHeader("kafka_dlt-exception-fqcn")).isNull();
        assertThat(counter("kafka.dlt.published")).isEqualTo(1);
        assertThat(counter("kafka.dlt.dropped")).isZero();
    }

    @Test
    void aRecordThatCanNeverBeDeadLettered_isSkipped_withOneErrorLine_andACounter() {
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(failed(new InvalidTopicException("payment.completed.DLT")));

        // returns normally: the error handler then commits past the record and the partition moves on
        recoverer.accept(record(5), null, unreadable("bad"));

        verify(bytesTemplate, times(1)).send(any(ProducerRecord.class));
        assertThat(pauses).isEmpty();
        assertThat(counter("kafka.dlt.dropped")).isEqualTo(1);
        assertThat(counter("kafka.dlt.published")).isZero();
        assertThat(lines(Level.ERROR)).hasSize(1);
        ILoggingEvent line = lines(Level.ERROR).get(0);
        assertThat(line.getThrowableProxy()).as("no stack trace per skipped record").isNull();
        assertThat(line.getFormattedMessage()).contains("payment.completed-0@5").contains("key 7").contains("cannot be written to payment.completed.DLT")
                .contains("skipping it so the partition keeps moving").contains("InvalidTopicException");
    }

    @Test
    void aFloodOfUndeliverableRecords_isNotALogFlood() {
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(failed(new InvalidTopicException("payment.completed.DLT")));

        for (int i = 0; i < 500; i++) {
            recoverer.accept(record(i), null, unreadable("bad " + i));
        }

        assertThat(counter("kafka.dlt.dropped")).isEqualTo(500);
        assertThat(lines(Level.ERROR)).hasSize(1);
        assertThat(lines(Level.ERROR).get(0).getThrowableProxy()).isNull();
    }

    @Test
    void aBrokerThatStaysUnreachable_isRetriedWithGrowingPauses_thenHandedBackToTheContainer() {
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(failed(new TimeoutException("Topic not present in metadata after 5000 ms")));

        assertThatThrownBy(() -> recoverer.accept(record(5), null, unreadable("bad")))
                .isInstanceOf(DeadLetterRecoverer.PublishFailed.class);

        // 1 + 4 attempts, 1 s, 2 s, 4 s, 8 s apart: a handful of calls instead of a loop at poll rate
        verify(bytesTemplate, times(5)).send(any(ProducerRecord.class));
        assertThat(pauses).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
        assertThat(counter("kafka.dlt.published")).isZero();
        assertThat(counter("kafka.dlt.dropped")).isZero();
        assertThat(lines(Level.WARN)).as("one throttled line, not one per attempt").hasSize(1);
        assertThat(lines(Level.WARN).get(0).getThrowableProxy()).isNull();
        assertThat(lines(Level.ERROR)).isEmpty();
    }

    @Test
    void aBrokerThatComesBack_getsTheRecord() {
        when(bytesTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new TimeoutException("metadata")))
                .thenReturn(failed(new TimeoutException("metadata")))
                .thenReturn(acknowledged());

        recoverer.accept(record(5), null, unreadable("bad"));

        verify(bytesTemplate, times(3)).send(any(ProducerRecord.class));
        assertThat(pauses).containsExactly(1_000L, 2_000L);
        assertThat(counter("kafka.dlt.published")).isEqualTo(1);
    }

    @Test
    void shuttingDownStopsTheRetries() {
        DeadLetterRecoverer real = new DeadLetterRecoverer(bytesTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1), meters);
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(failed(new TimeoutException("metadata")));
        real.close();

        long started = System.nanoTime();
        assertThatThrownBy(() -> real.accept(record(5), null, unreadable("bad")))
                .isInstanceOf(DeadLetterRecoverer.PublishFailed.class);

        assertThat((System.nanoTime() - started) / 1_000_000).as("no pause once closed").isLessThan(500);
        verify(bytesTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void exceptionDetailsInTheLog_cannotForgeLogLines() {
        when(bytesTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        recoverer.accept(record(5), null,
                unreadable("bad\r\n2026-01-01 00:00:00 [main] ERROR x - ADMIN LOGIN OK" + "x".repeat(2_000)));

        String message = lines(Level.WARN).get(0).getFormattedMessage();
        assertThat(message).doesNotContain("\r").doesNotContain("\n");
        assertThat(message.length()).isLessThan(600);
    }
}

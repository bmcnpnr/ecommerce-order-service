package com.ecommerce.order.kafka;

import com.ecommerce.order.logging.LogSanitizer;
import com.ecommerce.order.logging.LogThrottle;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * Dead-letters a failed record and makes sure that doing so can never become a retry loop.
 *
 * <p>Plain {@link DeadLetterPublishingRecoverer} throws when the dead-letter publication fails, and the
 * error handler answers by re-seeking to the same record: the record is polled, fails and is published
 * again at poll rate, logging three stack traces per round (measured at about 100 trace lines a second
 * for a record that no longer fits {@code max.request.size} once the exception headers are added) while
 * its partition stays blocked. That is the 2026-08-15 incident, reached through a different door. So:
 *
 * <ul>
 *   <li>The stack trace header is never added (it is what pushes a near-limit record over the size
 *       limit), and a record that is still too large is retried once with no exception headers at all,
 *       so anything that fit on the source topic fits on the dead-letter topic.</li>
 *   <li>A <b>transient</b> failure (broker unreachable, timeout) is retried here a few times with a
 *       pause, then rethrown so the container redelivers; the pauses keep the redelivery rate low.</li>
 *   <li>A <b>permanent</b> failure (record cannot be published at all, wrong topic name, no
 *       permission, serialization) skips the record: one throttled ERROR line with its coordinates, a
 *       metric, and the record stays on its source topic until retention, so nothing is lost silently
 *       and the partition keeps moving.</li>
 *   <li>Every log line here is throttled and carries no stack trace; the publication callbacks of
 *       spring-kafka that would log one are bypassed by overriding {@link #publish}.</li>
 * </ul>
 */
public class DeadLetterRecoverer extends DeadLetterPublishingRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    /** Transient failures are retried this many times here before the container is asked to redeliver. */
    static final int MAX_TRANSIENT_RETRIES = 4;
    static final long FIRST_PAUSE_MS = 1_000;
    static final long MAX_PAUSE_MS = 8_000;

    /** Sleeps; returns false when the wait was cut short (shutdown or interrupt) and retrying should stop. */
    @FunctionalInterface
    interface Pause {
        boolean sleep(long millis);
    }

    /** A failed dead-letter publication, classified. */
    static final class PublishFailed extends RuntimeException {
        private final boolean transientCause;
        private final boolean tooLarge;
        private final String topic;

        PublishFailed(Throwable cause, String topic) {
            super(cause);
            this.topic = topic;
            this.transientCause = isTransient(cause);
            this.tooLarge = causedBy(cause, RecordTooLargeException.class, RecordBatchTooLargeException.class);
        }

        boolean transientCause() {
            return transientCause;
        }

        boolean tooLarge() {
            return tooLarge;
        }

        /** The dead-letter topic the write was going to, when known. */
        String topic() {
            return topic;
        }
    }

    private final LogThrottle publishedLog = new LogThrottle(10, TimeUnit.SECONDS);
    private final LogThrottle problemLog = new LogThrottle(60, TimeUnit.SECONDS);
    private final ThreadLocal<Boolean> bare = ThreadLocal.withInitial(() -> false);
    private final MeterRegistry meters;
    private final Pause pause;
    private volatile boolean closed;

    public DeadLetterRecoverer(KafkaOperations<?, ?> template,
                               BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination,
                               MeterRegistry meters) {
        this(template, destination, meters, null);
    }

    DeadLetterRecoverer(KafkaOperations<?, ?> template,
                        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination,
                        MeterRegistry meters, Pause pause) {
        super(template, destination);
        this.meters = meters;
        this.pause = pause != null ? pause : this::sleep;
        excludeHeader(HeaderNames.HeadersToAdd.EX_STACKTRACE);
    }

    /** Wakes a pausing consumer thread so that shutdown does not wait for the pause to run out. */
    public void close() {
        closed = true;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception failure) {
        boolean bareRecord = false;
        PublishFailed problem = attempt(record, consumer, failure, bareRecord);
        if (problem != null && problem.tooLarge()) {
            // the exception headers pushed a record that fit the source topic over the size limit
            bareRecord = true;
            problem = attempt(record, consumer, failure, bareRecord);
        }
        long pauseMs = FIRST_PAUSE_MS;
        for (int retry = 0; problem != null && problem.transientCause() && retry < MAX_TRANSIENT_RETRIES; retry++) {
            long suppressed = problemLog.acquire("transient");
            if (suppressed >= 0) {
                log.warn("Dead-letter publication of {}-{}@{} failed ({}); retrying in {} ms{}", record.topic(),
                        record.partition(), record.offset(), describe(problem.getCause()), pauseMs,
                        LogThrottle.suppressedSuffix(suppressed));
            }
            if (!pause.sleep(pauseMs)) {
                break;
            }
            pauseMs = Math.min(MAX_PAUSE_MS, pauseMs * 2);
            problem = attempt(record, consumer, failure, bareRecord);
        }
        if (problem == null) {
            deadLettered(record, failure);
        } else if (problem.transientCause()) {
            // still unreachable: hand back to the container, which redelivers after this (bounded) call
            throw problem;
        } else {
            dropped(record, problem);
        }
    }

    private PublishFailed attempt(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception failure,
                                  boolean bareRecord) {
        bare.set(bareRecord);
        try {
            super.accept(record, consumer, failure);
            return null;
        } catch (PublishFailed e) {
            return e;
        } catch (RuntimeException e) {
            return new PublishFailed(e, null);
        } finally {
            bare.remove();
        }
    }

    /** Sends and waits for the acknowledgement, translating any failure; unlike the parent it logs nothing. */
    @Override
    protected void publish(ProducerRecord<Object, Object> outRecord, KafkaOperations<Object, Object> template,
                           ConsumerRecord<?, ?> inRecord) {
        try {
            template.send(outRecord).get(determineSendTimeout(template).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailed(e, outRecord.topic());
        } catch (ExecutionException e) {
            throw new PublishFailed(e.getCause() != null ? e.getCause() : e, outRecord.topic());
        } catch (TimeoutException e) {
            throw new PublishFailed(e, outRecord.topic());
        } catch (RuntimeException e) {
            throw new PublishFailed(e, outRecord.topic());
        }
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record, TopicPartition tp,
                                                                  Headers headers, byte[] key, byte[] value) {
        return super.createProducerRecord(record, tp, bare.get() ? bareHeaders(record) : headers, key, value);
    }

    /** The record's own headers plus where it came from; no exception details. */
    private static Headers bareHeaders(ConsumerRecord<?, ?> record) {
        Headers headers = new RecordHeaders(record.headers().toArray());
        headers.add("kafka_dlt-original-topic", record.topic().getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-partition", ByteBuffer.allocate(Integer.BYTES).putInt(record.partition()).array());
        headers.add("kafka_dlt-original-offset", ByteBuffer.allocate(Long.BYTES).putLong(record.offset()).array());
        return headers;
    }

    private void deadLettered(ConsumerRecord<?, ?> record, Exception failure) {
        count("kafka.dlt.published", record);
        long suppressed = publishedLog.acquire(record.topic());
        if (suppressed >= 0) {
            log.warn("Dead-lettered {}-{}@{} to the dead-letter topic: {}{}", record.topic(), record.partition(),
                    record.offset(), describe(failure), LogThrottle.suppressedSuffix(suppressed));
        }
    }

    private void dropped(ConsumerRecord<?, ?> record, PublishFailed problem) {
        count("kafka.dlt.dropped", record);
        long suppressed = problemLog.acquire("dropped:" + record.topic());
        if (suppressed >= 0) {
            log.error("A record ({}-{}@{}, key {}) cannot be written to {} ({}); skipping it so the partition keeps "
                            + "moving. It stays on its topic until retention, so it can be read again by resetting the "
                            + "group offset{}", record.topic(), record.partition(), record.offset(),
                    LogSanitizer.clean(record.key()), problem.topic() != null ? problem.topic() : record.topic() + ".DLT",
                    describe(problem.getCause()), LogThrottle.suppressedSuffix(suppressed));
        }
    }

    private void count(String name, ConsumerRecord<?, ?> record) {
        if (meters != null) {
            meters.counter(name, "topic", record.topic()).increment();
        }
    }

    private boolean sleep(long millis) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            while (!closed) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return true;
                }
                Thread.sleep(Math.min(200, TimeUnit.NANOSECONDS.toMillis(left) + 1));
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Class and first message line of the most specific cause, free of control characters and capped. */
    static String describe(Throwable t) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(t);
        String message = root.getMessage();
        String firstLine = message == null ? "" : message.lines().findFirst().orElse("");
        return LogSanitizer.clean(root.getClass().getSimpleName() + (firstLine.isEmpty() ? "" : ": " + firstLine));
    }

    /** Broker or network trouble that is expected to pass; everything else will fail the same way next time. */
    static boolean isTransient(Throwable t) {
        return causedBy(t, RetriableException.class, InterruptException.class, InterruptedException.class,
                TimeoutException.class, java.io.IOException.class);
    }

    @SafeVarargs
    private static boolean causedBy(Throwable t, Class<? extends Throwable>... types) {
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
        }
        return false;
    }
}

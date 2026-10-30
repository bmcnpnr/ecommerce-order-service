package com.ecommerce.order.outbox;

import com.ecommerce.order.logging.LogThrottle;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Publishes {@link OutboxEvent} rows to Kafka, oldest first, and deletes each one once Kafka has
 * acknowledged it. Delivery is at least once: a crash between the acknowledgement and the commit
 * publishes the row again, which the idempotent consumers absorb.
 *
 * <p>A transient failure (broker down, timeout, database trouble) stops the batch, so the order of
 * events is kept, and pauses the relay with an exponential back-off (1 s up to 30 s, reset by the
 * first success). A permanent failure (record too large, invalid topic, unserialisable) parks that
 * one row and carries on, so one bad row cannot block the rows behind it.
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final long INITIAL_BACKOFF_MS = 1_000;
    static final long MAX_BACKOFF_MS = 30_000;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final long COUNT_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final LongSupplier nanoClock;

    /** While the broker is down: at most one WARN a minute, however often the relay retries. */
    private final LogThrottle failureLog;

    private final Counter published;
    private final Counter failures;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong parkedCount = new AtomicLong();

    // Touched only by the scheduler thread (and by tests, one call at a time).
    private int consecutiveFailures;
    private long pausedUntil;
    private long countsRefreshedAt;
    private boolean countsKnown;

    @Autowired
    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       PlatformTransactionManager transactionManager,
                       MeterRegistry meterRegistry,
                       @Value("${outbox.relay.batch-size:100}") int batchSize,
                       // Above the producer's delivery.timeout.ms (30 s): the producer gives up first, this is the backstop.
                       @Value("${outbox.relay.send-timeout-ms:35000}") long sendTimeoutMs) {
        this(repository, kafkaTemplate, transactionManager, meterRegistry, batchSize, sendTimeoutMs, System::nanoTime,
                new LogThrottle(60, TimeUnit.SECONDS));
    }

    OutboxRelay(OutboxEventRepository repository,
                KafkaTemplate<String, String> kafkaTemplate,
                PlatformTransactionManager transactionManager,
                MeterRegistry meterRegistry,
                int batchSize,
                long sendTimeoutMs,
                LongSupplier nanoClock,
                LogThrottle failureLog) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.tx = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.sendTimeoutMs = sendTimeoutMs;
        this.nanoClock = nanoClock;
        this.failureLog = failureLog;
        this.published = Counter.builder("outbox.events.published")
                .description("Outbox events acknowledged by Kafka").register(meterRegistry);
        this.failures = Counter.builder("outbox.publish.failures")
                .description("Outbox relay cycles that ended in a transient failure").register(meterRegistry);
        // The gauges read counts the relay refreshes itself: a scrape never waits for the database,
        // which is exactly when the numbers matter most.
        Gauge.builder("outbox.events.pending", pendingCount, AtomicLong::doubleValue)
                .description("Outbox events waiting to be published").register(meterRegistry);
        Gauge.builder("outbox.events.parked", parkedCount, AtomicLong::doubleValue)
                .description("Outbox events parked after a permanent publish failure").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
    public void publishPending() {
        long now = nanoClock.getAsLong();
        if (consecutiveFailures > 0 && now - pausedUntil < 0) {
            return; // backing off
        }
        Batch batch;
        try {
            batch = tx.execute(status -> sendBatch());
        } catch (RuntimeException e) {
            // Could not lock or commit (database down or slow): nothing was lost, the rows are still there.
            onFailure(null, e, -1);
            return;
        }
        if (batch == null) {
            return;
        }
        if (batch.published() > 0) {
            published.increment(batch.published());
        }
        if (batch.interrupted()) {
            return; // shutting down: the transaction rolled back, the rows stay
        }
        if (batch.failure() != null) {
            onFailure(batch.failedTopic(), batch.failure(), batch.pendingAfterFailure());
        } else if (consecutiveFailures > 0) {
            log.info("Outbox relay resumed after {} failed attempts", consecutiveFailures);
            consecutiveFailures = 0;
        }
        refreshCounts(now);
    }

    private Batch sendBatch() {
        List<OutboxEvent> rows = repository.lockBatch(batchSize);
        int sent = 0;
        for (OutboxEvent row : rows) {
            try {
                kafkaTemplate.send(row.getTopic(), row.getEventKey(), row.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                repository.delete(row);
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Batch(sent, null, null, 0, true);
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                if (isPermanent(cause)) {
                    park(row, cause);
                    continue;
                }
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(describe(cause, MAX_ERROR_LENGTH));
                return new Batch(sent, cause, row.getTopic(), repository.countByParkedAtIsNull(), false);
            }
        }
        return new Batch(sent, null, null, 0, false);
    }

    private void park(OutboxEvent row, Throwable cause) {
        row.setParkedAt(LocalDateTime.now());
        row.setAttempts(row.getAttempts() + 1);
        row.setLastError(describe(cause, MAX_ERROR_LENGTH));
        // Once per row: a parked row is never retried, so this cannot repeat.
        log.error("Outbox event {} for topic {} can never be published ({}); parked. After fixing the cause, "
                        + "release it with: UPDATE outbox_event SET parked_at = NULL WHERE id = {}",
                row.getId(), row.getTopic(), describe(cause, 200), row.getId());
    }

    private void onFailure(String topic, Throwable cause, long pending) {
        failures.increment();
        consecutiveFailures++;
        long delayMs = backoffMillis(consecutiveFailures);
        pausedUntil = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        long suppressed = failureLog.acquire("outbox.relay.failure");
        if (consecutiveFailures == 1 || suppressed >= 0) {
            // No stack trace: a dead broker is an operating condition, not a bug. The reason is in the line.
            log.warn("Outbox relay cannot publish{}: {}; {} pending, retrying in {} ms{}",
                    topic == null ? "" : " to " + topic, describe(cause, 200),
                    pending < 0 ? "unknown number of events" : pending + " event(s)", delayMs,
                    LogThrottle.suppressedSuffix(Math.max(suppressed, 0)));
        }
    }

    /** 1 s, 2 s, 4 s ... capped at 30 s. */
    static long backoffMillis(int consecutiveFailures) {
        int shift = Math.min(Math.max(consecutiveFailures - 1, 0), 16);
        return Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << shift);
    }

    static boolean isPermanent(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof RecordTooLargeException || t instanceof RecordBatchTooLargeException
                    || t instanceof InvalidTopicException || t instanceof SerializationException) {
                return true;
            }
        }
        return false;
    }

    /** "KafkaException: Send failed (RecordTooLargeException: ...)": spring-kafka wraps the reason that matters. */
    private static String describe(Throwable t, int max) {
        String text = summary(t);
        Throwable root = t;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 10; depth++) {
            root = root.getCause();
        }
        if (root != t) {
            text += " (" + summary(root) + ")";
        }
        text = text.replaceAll("\\p{Cntrl}+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String summary(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private void refreshCounts(long now) {
        if (countsKnown && now - countsRefreshedAt < COUNT_REFRESH_NANOS) {
            return;
        }
        try {
            pendingCount.set(repository.countByParkedAtIsNull());
            parkedCount.set(repository.countByParkedAtIsNotNull());
            countsRefreshedAt = now;
            countsKnown = true;
        } catch (RuntimeException e) {
            // Keep the last known numbers; the failure is already reported by the main cycle.
        }
    }

    private record Batch(int published, Throwable failure, String failedTopic, long pendingAfterFailure, boolean interrupted) {
    }
}

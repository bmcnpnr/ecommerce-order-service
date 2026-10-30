package com.ecommerce.order.kafka;

import com.ecommerce.order.config.KafkaErrorHandling;
import com.ecommerce.order.logging.LogSanitizer;
import com.ecommerce.order.logging.LogThrottle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;

import java.util.concurrent.TimeUnit;

/**
 * Says what spring-kafka's {@code DefaultErrorHandler} does not: that the listener failed and is being
 * retried. The handler logs nothing between the first failure and the dead-letter publication, so a
 * consumer waiting for a dead database (it retries with back-off and no deadline) would otherwise look
 * healthy.
 *
 * <ul>
 *   <li>A dependency that does not answer (database down, pool exhausted, lock timeout): one WARN line,
 *       no stack trace, at most one per 10 s per exception class (the {@code L4} rule).</li>
 *   <li>Anything else: ERROR with the stack trace, once per failure site per 10 s window ({@code L3}).</li>
 *   <li>Records that cannot be read at all (deserialization, conversion) are not retried and are
 *       skipped here: {@link DeadLetterRecoverer} reports them once, when it dead-letters them.</li>
 * </ul>
 */
public class ListenerFailureLogger implements RetryListener {

    private static final Logger log = LoggerFactory.getLogger(ListenerFailureLogger.class);

    private final LogThrottle unavailable = new LogThrottle(10, TimeUnit.SECONDS);
    private final LogThrottle unexpected = new LogThrottle(10, TimeUnit.SECONDS);

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        if (isUnreadable(ex)) {
            return;
        }
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        if (KafkaErrorHandling.isTransient(ex)) {
            long suppressed = unavailable.acquire(cause.getClass().getName());
            if (suppressed >= 0) {
                log.warn("Dependency unavailable while handling {}-{}@{} (delivery attempt {}, retrying with back-off): {}{}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt,
                        LogSanitizer.clean(cause.toString()), LogThrottle.suppressedSuffix(suppressed));
            }
            return;
        }
        long suppressed = unexpected.acquire(LogThrottle.keyOf(cause));
        if (suppressed >= 0) {
            log.error("Listener failed for {}-{}@{} (delivery attempt {}): {}{}", record.topic(), record.partition(),
                    record.offset(), deliveryAttempt, LogSanitizer.clean(cause.toString()),
                    LogThrottle.suppressedSuffix(suppressed), cause);
        }
    }

    private static boolean isUnreadable(Throwable t) {
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof DeserializationException || t instanceof MessageConversionException
                    || t instanceof ConversionException || t instanceof MethodArgumentResolutionException) {
                return true;
            }
        }
        return false;
    }
}

package com.ecommerce.order.config;

import com.ecommerce.order.exception.Unavailability;
import com.ecommerce.order.kafka.DeadLetterRecoverer;
import com.ecommerce.order.kafka.ListenerFailureLogger;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What a listener container does with a record whose processing fails (R-1, K1). shipping-service runs the same
 * policy with the same {@link DeadLetterRecoverer} and {@link ListenerFailureLogger} (notification-service with an
 * equivalent pair), so the three consumers behave alike and report the same metrics ({@code kafka.dlt.published},
 * {@code kafka.dlt.dropped}).
 *
 * <ul>
 *   <li>A record that cannot be converted, or that keeps failing for a reason of its own, is retried
 *       twice a second apart and then published to {@code <topic>.DLT}; the partition moves on.</li>
 *   <li>A failure of the infrastructure behind the listener (database unreachable, lock timeout, broker
 *       hiccup) says nothing about the record, so the record is retried with a growing pause (1 s, 2 s ...
 *       30 s) for as long as it takes: the partition waits for the database instead of dead-lettering
 *       good events. Every single pause stays far below {@code max.poll.interval.ms}.</li>
 *   <li>Dead-lettering itself can fail, and must never become a retry loop: see {@link DeadLetterRecoverer}
 *       (bare copy for a record that is too large with its exception headers, bounded retries while the broker
 *       is away, a skipped and counted record when it can never be written, one throttled line, no stack trace).</li>
 * </ul>
 */
public final class KafkaErrorHandling {

    private KafkaErrorHandling() {
    }

    public static DefaultErrorHandler errorHandlerFor(DeadLetterRecoverer recoverer) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 2));
        handler.setBackOffFunction((record, exception) -> backOffFor(exception));
        // The handler itself says nothing between the first failure and the dead letter; this does, throttled.
        handler.setRetryListeners(new ListenerFailureLogger());
        return handler;
    }

    static BackOff backOffFor(Exception exception) {
        if (isTransient(exception)) {
            ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
            backOff.setMaxInterval(30_000);
            return backOff;
        }
        return new FixedBackOff(1000, 2);
    }

    /** Walks the cause chain: the listener's own exception wraps whatever the database or broker threw. */
    public static boolean isTransient(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException || t instanceof CannotGetJdbcConnectionException
                    || t instanceof RecoverableDataAccessException || t instanceof RetriableException
                    // a commit or rollback that failed because the connection was gone (a killed backend, a restart)
                    || Unavailability.isBrokenTransaction(t)) {
                return true;
            }
        }
        return false;
    }
}

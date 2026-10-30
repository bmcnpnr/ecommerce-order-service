package com.ecommerce.order.exception;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Tells "something this service depends on does not answer" from "this service has a bug" (L4).
 * The first is an operating condition: the caller gets a 503 and the log gets one throttled line without
 * a stack trace. The second is logged with its stack trace, throttled.
 */
public final class Unavailability {

    private Unavailability() {
    }

    /**
     * "CannotCreateTransactionException (PSQLException: Connection to db:5432 refused ...)": enough for an operator to
     * see what is down, from a single log line, without the stack trace. Control characters are stripped and the
     * text is capped, because the message of a downstream exception is not ours.
     */
    public static String describe(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 10; depth++) {
            root = root.getCause();
        }
        String text = failure.getClass().getSimpleName();
        if (root != failure) {
            text += " (" + root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage()) + ")";
        }
        text = text.replaceAll("\\p{Cntrl}+", " ");
        return text.length() <= 300 ? text : text.substring(0, 300);
    }

    /**
     * The first exception in the cause chain that means a dependency (database, broker, product-service)
     * is not answering, or {@code null} when the failure is something else. Optimistic-lock conflicts are
     * excluded on purpose although Spring files them under {@link TransientDataAccessException}: they are
     * a conflict between two callers (409), not an outage.
     */
    public static Throwable dependencyFailure(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof org.springframework.dao.OptimisticLockingFailureException) {
                return null;
            }
            if (t instanceof CannotGetJdbcConnectionException || t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException || t instanceof TransientDataAccessException
                    || t instanceof TimeoutException || isBrokenTransaction(t) || isPeerFailure(t)) {
                return t;
            }
        }
        return null;
    }

    /** product-service did not give an answer: refused, timed out, no instance registered, 503, or the breaker is open. */
    public static boolean isPeerUnavailable(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 10; t = t.getCause(), depth++) {
            if (isPeerFailure(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPeerFailure(Throwable t) {
        if (t instanceof CallNotPermittedException || t instanceof RetryableException) {
            return true;
        }
        // -1: the call never completed; 503: the peer (or the load balancer, when no instance is registered) said so.
        return t instanceof FeignException fe && (fe.status() == -1 || fe.status() == 503);
    }

    /**
     * A transaction that could not be committed or rolled back because the connection under it was gone. When a
     * database backend is killed, or Postgres restarts, mid-transaction, the rollback fails as well and Spring throws a
     * {@link TransactionSystemException} that replaces the original error (Hibernate's own
     * {@link org.hibernate.TransactionException} when nothing wraps it). Either is an outage only with a connection
     * underneath - a SQLException that is not a data or syntax error, or an I/O error - or when the error it replaced
     * was one; otherwise it is a bug and stays a 500.
     */
    public static boolean isBrokenTransaction(Throwable t) {
        if (!(t instanceof TransactionSystemException || t instanceof org.hibernate.TransactionException)) {
            return false;
        }
        if (hasConnectionCause(t)) {
            return true;
        }
        return t instanceof TransactionSystemException tse && tse.getApplicationException() != null
                && dependencyFailure(tse.getApplicationException()) != null;
    }

    private static boolean hasConnectionCause(Throwable top) {
        int depth = 0;
        for (Throwable c = top.getCause(); c != null && depth < 10; c = c.getCause(), depth++) {
            if (c instanceof SQLException sql && !isDataOrSyntaxError(sql)) {
                return true;
            }
            if (c instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** SQLState classes 21 (cardinality), 22 (data), 23 (integrity constraint) and 42 (syntax/access): the statement was wrong, not the connection. */
    private static boolean isDataOrSyntaxError(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("21") || state.startsWith("22") || state.startsWith("23") || state.startsWith("42"));
    }
}

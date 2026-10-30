package com.ecommerce.order.exception;

import jakarta.persistence.PersistenceException;
import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionSystemException;

import java.io.EOFException;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a database backend is killed (or Postgres restarts) mid-transaction the rollback fails too, and Spring throws
 * a TransactionSystemException that replaces the original error. That must read as "the database is gone" (503, one
 * throttled line), not as a bug (500 and a stack trace of hundreds of lines).
 */
class UnavailabilityTest {

    private static TransactionSystemException rollbackFailed(Throwable underneath) {
        return new TransactionSystemException("Could not roll back JPA transaction",
                new PersistenceException("Unable to rollback against JDBC Connection", underneath));
    }

    static Stream<Throwable> outages() {
        TransactionSystemException replacedAnOutage = new TransactionSystemException("Could not roll back JPA transaction",
                new PersistenceException("rollback failed"));
        replacedAnOutage.initApplicationException(new DataAccessResourceFailureException("connection reset"));
        return Stream.of(
                rollbackFailed(new TransactionException("Unable to rollback against JDBC Connection",
                        new SQLException("This connection has been closed.", "08003"))),
                rollbackFailed(new SQLException("terminating connection due to administrator command", "57P01")),
                rollbackFailed(new SQLException("connection closed")),                       // no SQLState at all
                rollbackFailed(new SocketException("Connection reset")),
                rollbackFailed(new EOFException()),
                new TransactionSystemException("Could not commit JPA transaction", new SQLException("An I/O error occurred", "08006")),
                new TransactionException("JDBC commit failed", new SQLException("This connection has been closed.", "08003")),
                replacedAnOutage,
                // wrapped by something else on the way up
                new IllegalStateException("tx failed", rollbackFailed(new SQLException("terminating connection", "57P02"))));
    }

    @ParameterizedTest
    @MethodSource("outages")
    void aTransactionThatBrokeWithItsConnection_isAnOutage(Throwable failure) {
        assertThat(Unavailability.dependencyFailure(failure)).as(Unavailability.describe(failure)).isNotNull();
    }

    static Stream<Throwable> bugs() {
        TransactionSystemException replacedABug = new TransactionSystemException("Could not roll back JPA transaction",
                new PersistenceException("rollback failed"));
        replacedABug.initApplicationException(new IllegalStateException("bug"));
        return Stream.of(
                rollbackFailed(new IllegalStateException("not a connection problem")),
                new TransactionSystemException("Could not commit JPA transaction"),               // nothing underneath
                new TransactionException("no cause"),
                rollbackFailed(new SQLException("duplicate key value violates unique constraint", "23505")),
                rollbackFailed(new SQLException("relation \"nope\" does not exist", "42P01")),
                rollbackFailed(new SQLException("value too long for type character varying(255)", "22001")),
                replacedABug);
    }

    @ParameterizedTest
    @MethodSource("bugs")
    void aTransactionFailureWithoutAConnectionUnderneath_staysABug(Throwable failure) {
        assertThat(Unavailability.dependencyFailure(failure)).as(Unavailability.describe(failure)).isNull();
    }

    @Test
    void aVersionConflict_isStillNotAnOutage() {
        assertThat(Unavailability.dependencyFailure(new ObjectOptimisticLockingFailureException(Object.class, 1L))).isNull();
    }

    @Test
    void theLogLineNamesTheConnectionProblem_notJustTheWrapper() {
        TransactionSystemException failure = rollbackFailed(new SQLException("This connection has been closed.", "08003"));

        assertThat(Unavailability.describe(Unavailability.dependencyFailure(failure)))
                .startsWith("TransactionSystemException (SQLException: This connection has been closed.");
    }
}

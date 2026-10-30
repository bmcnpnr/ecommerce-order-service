package com.ecommerce.order.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerFailureLoggerTest {

    /** Distinct exception classes give distinct throttle keys; each test uses its own. */
    static class FirstBug extends RuntimeException {
        FirstBug(String message) {
            super(message);
        }
    }

    static class SecondBug extends RuntimeException {
        SecondBug(String message) {
            super(message);
        }
    }

    private final ListenerFailureLogger listener = new ListenerFailureLogger();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ListenerFailureLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.completed", 0, 5L, "7", "{}");

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static Exception wrapped(Throwable cause) {
        return new ListenerExecutionFailedException("Listener method threw", cause);
    }

    @Test
    void aFailingListener_isReportedOnce_withItsStackTrace_nomatterHowOftenItRepeats() {
        for (int attempt = 1; attempt <= 1_000; attempt++) {
            listener.failedDelivery(record, wrapped(new FirstBug("channel down")), attempt);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getThrowableProxy()).isNotNull();
        assertThat(line.getFormattedMessage()).contains("payment.completed-0@5").contains("FirstBug: channel down");
    }

    @Test
    void aDatabaseThatDoesNotAnswer_isOneShortWarnLine_withoutAStackTrace_nomatterHowOftenItRepeats() {
        for (int attempt = 1; attempt <= 1_000; attempt++) {
            listener.failedDelivery(record, wrapped(new CannotCreateTransactionException("Hikari: connection is not available")), attempt);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getThrowableProxy()).isNull();
        assertThat(line.getFormattedMessage()).contains("Dependency unavailable").contains("payment.completed-0@5")
                .contains("CannotCreateTransactionException");
    }

    /** The line says "retrying with back-off", so exactly the failures the error handler backs off for get it. */
    @Test
    void whatTheErrorHandlerBacksOffFor_isAWarnLine_notAnErrorWithAStackTrace() {
        listener.failedDelivery(record, wrapped(new org.springframework.transaction.TransactionSystemException(
                "Could not roll back JPA transaction", new jakarta.persistence.PersistenceException("Unable to rollback",
                        new java.sql.SQLException("This connection has been closed.", "08003")))), 1);
        listener.failedDelivery(record, wrapped(new org.springframework.orm.ObjectOptimisticLockingFailureException(Object.class, 1L)), 1);
        listener.failedDelivery(record, wrapped(new org.apache.kafka.common.errors.TimeoutException("broker did not answer")), 1);

        assertThat(appender.list).hasSize(3);
        assertThat(appender.list).allSatisfy(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getThrowableProxy()).isNull();
        });
    }

    @Test
    void aFailureThatIsNotInfrastructure_isAnErrorEvenWhenWrappedInATransactionFailure() {
        listener.failedDelivery(record, wrapped(new org.springframework.transaction.TransactionSystemException(
                "Could not commit JPA transaction", new IllegalStateException("bug"))), 1);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    void differentFailures_areReportedSeparately() {
        listener.failedDelivery(record, wrapped(new FirstBug("a")), 1);
        listener.failedDelivery(record, wrapped(new SecondBug("b")), 1);

        assertThat(appender.list).hasSize(2);
    }

    @Test
    void recordsThatCannotBeReadAreLeftToTheDeadLetterRecoverer() {
        listener.failedDelivery(record, wrapped(new DeserializationException("bad", new byte[0], false, new RuntimeException())), 1);
        listener.failedDelivery(record, wrapped(new MessageConversionException("bad")), 1);

        assertThat(appender.list).isEmpty();
    }
}

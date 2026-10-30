package com.ecommerce.order.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Counts what the application itself writes to the log while something is going wrong: every WARN or
 * ERROR line from our own code, spring-kafka, Hibernate and Hikari, and how many stack-trace lines they
 * carry. Kafka client and Testcontainers chatter is left out (it is bounded by the client settings, not by us).
 */
final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogCapture() {
        appender.start();
        root.addAppender(appender);
    }

    List<ILoggingEvent> problems() {
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(e -> e.getLoggerName().startsWith("com.ecommerce") || e.getLoggerName().startsWith("org.springframework.kafka")
                        || e.getLoggerName().startsWith("org.hibernate") || e.getLoggerName().startsWith("com.zaxxer")
                        || e.getLoggerName().startsWith("org.springframework.transaction"))
                .toList();
    }

    long stackTraceLines() {
        long lines = 0;
        for (ILoggingEvent event : problems()) {
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                lines += 1 + (t.getStackTraceElementProxyArray() == null ? 0 : t.getStackTraceElementProxyArray().length);
            }
        }
        return lines;
    }

    String describe() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent e : problems()) {
            out.append('\n').append(e.getLevel()).append(' ').append(e.getLoggerName()).append(" :: ")
                    .append(e.getFormattedMessage());
        }
        return out.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}

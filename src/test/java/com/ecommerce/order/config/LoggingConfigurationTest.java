package com.ecommerce.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed logging defaults (L1, L8) and the framework loggers that would otherwise write a line, or a whole
 * stack trace, per failed request when the database dies. Read straight from application.yaml, because a value that
 * is not quoted does not survive YAML: an unquoted OFF is the boolean false.
 */
class LoggingConfigurationTest {

    private static String setting(String key) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        Object value = sources.get(0).getProperty(key);
        return value == null ? null : String.valueOf(value);
    }

    @Test
    void applicationCodeLogsAtInfoByDefault() throws IOException {
        assertThat(setting("logging.level.com.ecommerce")).isEqualTo("INFO");
    }

    @Test
    void logPatternIsThePlatformsSingleFormat() throws IOException {
        assertThat(setting("logging.pattern.console"))
                .isEqualTo("%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-}] [%X{traceId:-}] %-5level %logger{36} - %msg%n");
    }

    @Test
    void hibernateJdbcErrorLoggerIsOff_twoWarnLinesPerFailedStatementOtherwise() throws IOException {
        assertThat(setting("logging.level.org.hibernate.orm.jdbc.error")).isEqualTo("OFF");
    }

    @Test
    void springsOverriddenApplicationExceptionTraceIsOff_hundredsOfLinesPerRequestWhenTheBackendDies() throws IOException {
        assertThat(setting("logging.level.org.springframework.transaction.interceptor.TransactionInterceptor")).isEqualTo("OFF");
        assertThat(setting("logging.level.org.springframework.transaction.support.TransactionTemplate")).isEqualTo("OFF");
    }

    @Test
    void connectionsAreWaitedForAtMostFiveSeconds() throws IOException {
        assertThat(setting("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
    }

    @Test
    void aDatabaseThatStopsAnsweringIsGivenUpOn_notWaitedForUntilTheOperatingSystemDoes() throws IOException {
        // pgjdbc has no socket timeout by default; ConnectionSettingsIT checks that the driver really applies these
        assertThat(setting("spring.datasource.hikari.data-source-properties.connectTimeout")).isEqualTo("5");
        assertThat(setting("spring.datasource.hikari.data-source-properties.socketTimeout")).isEqualTo("30");
        assertThat(setting("spring.datasource.hikari.data-source-properties.tcpKeepAlive")).isEqualTo("true");
    }

    @Test
    void aShutdownLetsTheScheduledJobThatIsRunningFinish() throws IOException {
        assertThat(setting("spring.task.scheduling.shutdown.await-termination")).isEqualTo("true");
        assertThat(setting("spring.task.scheduling.shutdown.await-termination-period")).isEqualTo("20s");
    }
}

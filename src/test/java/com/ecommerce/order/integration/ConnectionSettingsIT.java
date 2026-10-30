package com.ecommerce.order.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production connection pool is bounded: nothing waits for a database that does not answer for minutes.
 * pgjdbc has no socket timeout by default, so a database that stops answering (dropped packets, a frozen host)
 * would hold a request thread, the relay or a Kafka consumer thread until the operating system gives up.
 */
class ConnectionSettingsIT extends ApplicationTestBase {

    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    @Autowired ThreadPoolTaskScheduler taskScheduler;

    @Test
    void aRequestWaitsAtMostFiveSecondsForAConnection_andNoReadBlocksForever() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        assertThat(pool.getConnectionTimeout()).isEqualTo(5_000);
        Properties driver = pool.getDataSourceProperties();
        assertThat(driver.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(driver.getProperty("socketTimeout")).isEqualTo("30");
        assertThat(driver.getProperty("tcpKeepAlive")).isEqualTo("true");
        // and the driver really applied it: JDBC reports the socket read timeout of the live connection
        try (var connection = pool.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(30_000);
        }
    }

    /** SIGTERM lets the relay and the expiry job finish the pass they are in, instead of interrupting it mid-batch. */
    @Test
    void aShutdownWaitsForTheScheduledJobsThatAreRunning() {
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination-period")).isEqualTo("20s");
        // and the scheduler that runs the relay really was built with it
        assertThat(ReflectionTestUtils.getField(taskScheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(taskScheduler, "awaitTerminationMillis")).isEqualTo(20_000L);
    }

    /** The relay and the expiry job each get a thread: an expiry pass waiting on product-service can't hold back events. */
    @Test
    void theRelayAndTheExpiryJobDoNotShareOneThread() {
        assertThat(taskScheduler.getPoolSize()).isEqualTo(2);
    }
}

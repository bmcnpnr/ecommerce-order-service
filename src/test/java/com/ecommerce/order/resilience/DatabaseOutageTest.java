package com.ecommerce.order.resilience;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service against a database that is not there (L3, L4, D1), without Docker: the datasource points at a
 * closed port. Every request fails the same way, which is exactly the situation in which a log line per
 * request (or worse, a stack trace per request) turns an outage into a disk-filling incident.
 *
 * <p>Asserted from the point of view of an operator: the caller gets a 503 with a fixed message promptly
 * (not a 500 after 30 s), and the request threads write only a handful of WARN/ERROR lines for 60 requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:1/none",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "outbox.relay.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:1",
        "management.tracing.sampling.probability=0.0"
})
class DatabaseOutageTest {

    @LocalServerPort int port;

    private final ExecutorService clients = Executors.newFixedThreadPool(8);

    private ResponseEntity<String> request(HttpMethod method, String path, String user, String role) {
        return RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(new JdkClientHttpRequestFactory()).build()
                .method(method).uri(path)
                .headers(h -> {
                    h.set("X-Username", user);
                    h.set("X-User-Role", role);
                })
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    @Test
    void sixtyRequestsAgainstADeadDatabase_are503WithAFixedMessage_andCostAHandfulOfLogLines() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        List<CompletableFuture<ResponseEntity<String>>> calls = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < 20; i++) {
                // the owner check reads the database outside a transaction, the admin path starts a transaction,
                // and creating an order writes: three different places the connection can fail
                calls.add(CompletableFuture.supplyAsync(() -> request(HttpMethod.GET, "/api/v1/orders/1", "alice", "CUSTOMER"), clients));
                calls.add(CompletableFuture.supplyAsync(() -> request(HttpMethod.GET, "/api/v1/orders/1", "root", "ADMIN"), clients));
                calls.add(CompletableFuture.supplyAsync(() -> request(HttpMethod.POST, "/api/v1/orders", "alice", "CUSTOMER"), clients));
            }
            for (CompletableFuture<ResponseEntity<String>> call : calls) {
                ResponseEntity<String> response = call.get();
                assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(503);
                assertThat(JsonMapper.builder().build().readTree(response.getBody()).get("message").asString())
                        .isEqualTo("Service temporarily unavailable");
            }
        } finally {
            root.detachAppender(logs);
            clients.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // what the request threads wrote at WARN or above: Hibernate and Hikari included, not only our handler
        List<ILoggingEvent> loud = logs.list.stream()
                .filter(e -> e.getThreadName().startsWith("http-nio"))
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        assertThat(loud).as("WARN/ERROR lines written by request threads for 60 failing requests: %s",
                loud.stream().map(e -> e.getLoggerName() + ": " + e.getFormattedMessage()).toList()).hasSizeLessThanOrEqualTo(6);
        assertThat(loud).as("a dead dependency is not a bug: no stack trace")
                .allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
        assertThat(elapsedMs).as("60 requests against a dead database, 8 at a time: bounded by the 250 ms test connection timeout, not 30 s each")
                .isLessThan(30_000);
    }
}

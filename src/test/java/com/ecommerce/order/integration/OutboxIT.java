package com.ecommerce.order.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.event.OrderPlacedEvent;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.outbox.OutboxEvent;
import com.ecommerce.order.outbox.OutboxEventRepository;
import com.ecommerce.order.outbox.OutboxRelay;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox against real Postgres and Kafka: an event exists exactly when the change that
 * caused it commits, and the relay delivers it afterwards, once, in order. (Kafka being unreachable is
 * {@code OutboxKafkaOutageIT}.)
 */
class OutboxIT extends ApplicationTestBase {

    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OrderEventProducer producer;
    @Autowired private OrderRepository orders;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OutboxRelay relay; // present: the IT profile runs the real relay

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long outboxRows(String key) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key);
    }

    private static OrderPlacedEvent placed(long orderId) {
        return OrderPlacedEvent.builder().orderId(orderId).customerId("cust-outbox").totalAmount(new BigDecimal("10.00"))
                .orderDate(LocalDateTime.now()).items(List.of()).build();
    }

    @Test
    void confirmingAnOrder_publishesOrderPlaced_andTheRowIsGoneOnceKafkaAcknowledged() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/81")).willReturn(okJson(
                "{\"id\":81,\"sku\":\"SKU-81\",\"name\":\"Trail Shoe\",\"price\":12.50,\"stockQuantity\":10,\"status\":\"ACTIVE\"}")));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/81/stock")).willReturn(okJson("{\"id\":81}")));
        long orderId = JSON.readTree(callAsAdmin(HttpMethod.POST, "/api/v1/orders?customerId=cust-outbox-1", null).getBody()).get("orderId").asLong();
        callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 81, "quantity", 1));

        ResponseEntity<String> confirmed = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null);

        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        JsonNode payload = JSON.readTree(awaitRecord("order.placed", Long.toString(orderId), KAFKA_TIMEOUT).value());
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payload.get("orderDate").isArray()).as("LocalDateTime stays an int array on the wire").isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxRows(Long.toString(orderId))).isZero());
        assertThat(jdbc.queryForObject("select status from orders where order_id = ?", String.class, orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void theEventIsInvisibleUntilTheChangeCommits_andDeliveredOnceAfterwards() throws Exception {
        long orderId = 910_000_000L + (long) (Math.random() * 1_000_000);
        String key = Long.toString(orderId);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        var otherConnection = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                producer.publishOrderPlaced(placed(orderId));
                assertThat(outboxRows(key)).as("visible inside the writing transaction").isEqualTo(1);
                // another connection (another thread has no transaction bound) sees nothing before the commit
                long seenElsewhere = uncommittedViewFromAnotherConnection(otherConnection, key);
                assertThat(seenElsewhere).as("not visible to the relay before commit").isZero();
            });
        } finally {
            otherConnection.shutdown();
        }

        assertThat(awaitRecord("order.placed", key, KAFKA_TIMEOUT).key()).isEqualTo(key);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxRows(key)).isZero());
    }

    private long uncommittedViewFromAnotherConnection(java.util.concurrent.ExecutorService executor, String key) {
        try {
            return executor.submit(() -> jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRolledBackTransaction_publishesNothing() {
        long orderId = 920_000_000L + (long) (Math.random() * 1_000_000);
        String key = Long.toString(orderId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orders.save(Order.builder().customerId("cust-rollback").status(OrderStatus.CONFIRMED).orderDate(LocalDateTime.now())
                    .totalAmount(BigDecimal.ZERO).build());
            producer.publishOrderPlaced(placed(orderId));
            status.setRollbackOnly();
        });

        assertThat(outboxRows(key)).isZero();
        assertThatThrownBy(() -> awaitRecord("order.placed", key, Duration.ofSeconds(4)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No record with key " + key);
    }

    @Test
    void aRowThatCanNeverBePublished_isParkedOnce_andTheRowBehindItIsStillDelivered() {
        String poisonKey = "poison-" + uniq();
        String healthyKey = "healthy-" + uniq();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        relayLogger.addAppender(logs);
        OutboxEvent poison = null;
        try {
            // 2 MB: larger than the producer's max.request.size (1 MB), so Kafka's client refuses it for good
            poison = outbox.save(OutboxEvent.builder().topic("order.placed").eventKey(poisonKey)
                    .payload("{\"filler\":\"" + "x".repeat(2_000_000) + "\"}").createdAt(LocalDateTime.now()).build());
            outbox.save(OutboxEvent.builder().topic("order.placed").eventKey(healthyKey)
                    .payload("{\"orderId\":1}").createdAt(LocalDateTime.now()).build());

            assertThat(awaitRecord("order.placed", healthyKey, KAFKA_TIMEOUT).value()).isEqualTo("{\"orderId\":1}");
            long poisonId = poison.getId();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select parked_at is not null from outbox_event where id = ?", Boolean.class, poisonId)).isTrue());

            Map<String, Object> row = jdbc.queryForMap("select attempts, last_error from outbox_event where id = ?", poisonId);
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).contains("RecordTooLargeException");
            assertThat(outboxRows(healthyKey)).as("delivered rows are deleted").isZero();
            // it is not retried: after a few relay cycles it was still attempted once, and logged once
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select attempts from outbox_event where id = ?", Integer.class, poisonId)).isEqualTo(1));
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(1);
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow().getFormattedMessage())
                    .contains("UPDATE outbox_event SET parked_at = NULL WHERE id = " + poisonId);
        } finally {
            relayLogger.detachAppender(logs);
            if (poison != null) {
                outbox.deleteById(poison.getId());
            }
        }
    }

    @Test
    void theOutboxMetricsAreExported() {
        ResponseEntity<String> scrape = call(HttpMethod.GET, "/actuator/prometheus", null);

        assertThat(scrape.getStatusCode().value()).isEqualTo(200);
        assertThat(scrape.getBody()).contains("outbox_events_pending", "outbox_events_parked",
                "outbox_events_published_total", "outbox_publish_failures_total");
        // the relay ticks twice a second for ever: each tick must not be a trace and a timer of its own
        assertThat(scrape.getBody()).doesNotContain("tasks_scheduled_execution");
    }
}

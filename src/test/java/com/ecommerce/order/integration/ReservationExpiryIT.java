package com.ecommerce.order.integration;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.order.service.OrderService.ExpiryOutcome;
import com.ecommerce.order.service.ReservationExpiryJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Abandoned carts against real Postgres, Kafka and a stubbed product-service: a PENDING cart nobody touched for
 * longer than the TTL (24 h) is cancelled and its stock returned once; a recently touched cart, a CONFIRMED order
 * (its payment may still be on its way) and a PAID order are left alone; and two replicas racing for the same carts
 * return each cart's stock once and announce each cancellation once.
 */
class ReservationExpiryIT extends ApplicationTestBase {

    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(30);

    @Autowired private ReservationExpiryJob job;
    @Autowired private OrderService service;
    @Autowired private OrderRepository orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meters;

    private static void stubProduct(long productId) {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/" + productId)).willReturn(okJson(
                "{\"id\":%d,\"sku\":\"SKU-%d\",\"name\":\"Shoe\",\"price\":2.00,\"stockQuantity\":100,\"status\":\"ACTIVE\"}".formatted(productId, productId))));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/" + productId + "/stock")).willReturn(okJson("{\"id\":" + productId + "}")));
    }

    private static int stockRequests(long productId) {
        return PRODUCT_SERVICE.findAll(patchRequestedFor(urlEqualTo("/internal/v1/products/" + productId + "/stock"))).size();
    }

    /** An order with one line, last changed {@code idleHours} ago. */
    private long seed(OrderStatus status, long productId, int quantity, long idleHours) {
        Order order = Order.builder().customerId("cust-expiry").status(status).orderDate(LocalDateTime.now().minusHours(idleHours))
                .totalAmount(BigDecimal.ZERO).build();
        order.addItem(OrderItem.builder().productId(productId).productName("Shoe")
                .productPrice(new BigDecimal("2.00")).quantity(quantity).build());
        long id = orders.saveAndFlush(order).getOrderId();
        jdbc.update("update orders set updated_at = ? where order_id = ?", LocalDateTime.now().minusHours(idleHours), id);
        return id;
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("select status from orders where order_id = ?", String.class, orderId);
    }

    private double expiredCounter() {
        return meters.get("orders.reservations.expired").counter().count();
    }

    /** Every {@code order.cancelled} record on the topic within the window, by key. */
    private static Map<String, List<ConsumerRecord<String, String>>> cancelledEvents(Duration window) {
        Map<String, List<ConsumerRecord<String, String>>> byKey = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "expiry-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("order.cancelled"));
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> byKey.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r));
            }
        }
        return byKey;
    }

    @Test
    void anAbandonedCartIsCancelled_itsStockReturnedOnce_andNothingElseIsTouched() {
        for (long product = 81; product <= 85; product++) {
            stubProduct(product);
        }
        long abandoned = seed(OrderStatus.PENDING, 81L, 2, 30);           // 30 h idle, TTL is 24 h
        long justUnderTheTtl = seed(OrderStatus.PENDING, 82L, 1, 23);     // touched 23 h ago
        long fresh = seed(OrderStatus.PENDING, 83L, 1, 0);
        long oldConfirmed = seed(OrderStatus.CONFIRMED, 84L, 1, 72);      // never expired: the payment may be on its way
        long oldPaid = seed(OrderStatus.PAID, 85L, 1, 72);
        double before = expiredCounter();

        job.expireAbandonedCarts();

        assertThat(statusOf(abandoned)).isEqualTo("CANCELLED");
        assertThat(statusOf(justUnderTheTtl)).isEqualTo("PENDING");
        assertThat(statusOf(fresh)).isEqualTo("PENDING");
        assertThat(statusOf(oldConfirmed)).isEqualTo("CONFIRMED");
        assertThat(statusOf(oldPaid)).isEqualTo("PAID");
        // the stock goes back right after the commit, once
        await().atMost(KAFKA_TIMEOUT).untilAsserted(() -> assertThat(stockRequests(81L)).isEqualTo(1));
        PRODUCT_SERVICE.verify(1, patchRequestedFor(urlEqualTo("/internal/v1/products/81/stock")).withRequestBody(equalToJson("{\"delta\":2}")));
        for (long untouched : new long[]{82, 83, 84, 85}) {
            assertThat(stockRequests(untouched)).as("stock requests for product %d", untouched).isZero();
        }
        // order.cancelled goes out through the outbox, like a cancel the customer asked for; only for the abandoned cart
        assertThat(awaitRecord("order.cancelled", Long.toString(abandoned), KAFKA_TIMEOUT).value()).contains("\"orderId\":" + abandoned);
        assertThatThrownBy(() -> awaitRecord("order.cancelled", Long.toString(fresh), Duration.ofSeconds(3))).isInstanceOf(AssertionError.class);
        assertThat(expiredCounter()).isGreaterThanOrEqualTo(before + 1);

        // the next run finds nothing more to do and returns nothing a second time
        job.expireAbandonedCarts();
        assertThat(stockRequests(81L)).isEqualTo(1);
    }

    @Test
    void theCountIsOnTheMetricsEndpoint() {
        String scrape = call(HttpMethod.GET, "/actuator/prometheus", null).getBody();

        assertThat(scrape).contains("orders_reservations_expired_total");
    }

    /** Two replicas select the same carts and race for them: each cart is cancelled by one of them, announced and refunded once. */
    @Test
    void twoReplicasRacingForTheSameCarts_cancelEachOnce_returnItsStockOnce_andAnnounceItOnce() throws Exception {
        stubProduct(86L);
        List<Long> carts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            carts.add(seed(OrderStatus.PENDING, 86L, 1, 30));
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        ExecutorService replicas = Executors.newFixedThreadPool(2);
        try {
            for (long cart : carts) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<ExpiryOutcome> a = replicas.submit(() -> {
                    start.await();
                    return service.expireAbandonedCart(cart, cutoff);
                });
                Future<ExpiryOutcome> b = replicas.submit(() -> {
                    start.await();
                    return service.expireAbandonedCart(cart, cutoff);
                });
                assertThat(List.of(a.get(), b.get())).as("outcomes for cart %d", cart).containsExactlyInAnyOrder(ExpiryOutcome.EXPIRED, ExpiryOutcome.SKIPPED);
            }
        } finally {
            replicas.shutdownNow();
        }

        for (long cart : carts) {
            assertThat(statusOf(cart)).isEqualTo("CANCELLED");
        }
        assertThat(stockRequests(86L)).as("one stock return per cart, not per replica").isEqualTo(carts.size());
        Map<String, List<ConsumerRecord<String, String>>> events = cancelledEvents(Duration.ofSeconds(8));
        for (long cart : carts) {
            assertThat(events.getOrDefault(Long.toString(cart), List.of())).as("order.cancelled records for cart %d", cart).hasSize(1);
        }
    }
}

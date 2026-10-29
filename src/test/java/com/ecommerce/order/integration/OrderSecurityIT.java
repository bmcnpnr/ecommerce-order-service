package com.ecommerce.order.integration;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Security tests against the full stack (real Postgres, real Kafka, product-service via WireMock).
 * A CUSTOMER is {@code alice}, the victim is {@code bob}; identity is sent the way the gateway
 * forwards it ({@code X-Username}, {@code X-User-Id}, {@code X-User-Role}).
 *
 * <p>Tests assert the secure behaviour. Those that the current code fails are {@code @Disabled}
 * with the finding id, after being run enabled to confirm they fail for that reason alone.
 */
@Tag("security")
class OrderSecurityIT extends ApplicationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;

    // ---- helpers ---------------------------------------------------------------------------------

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<String> as(String user, String role, HttpMethod method, String path, Object body) {
        var spec = http.method(method).uri(path).headers(h -> {
            if (user != null) {
                h.add("X-Username", user);
                h.add("X-User-Id", Integer.toString(Math.abs(user.hashCode()) % 100000));
            }
            if (role != null) {
                h.add("X-User-Role", role);
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> asCustomer(String user, HttpMethod method, String path, Object body) {
        return as(user, "CUSTOMER", method, path, body);
    }

    private long seedOrder(String customerId, OrderStatus status, String unitPrice, int quantity) {
        Order order = Order.builder().customerId(customerId).status(status).orderDate(LocalDateTime.now())
                .totalAmount(BigDecimal.ZERO).build();
        order.addItem(OrderItem.builder().productId(1L).productName("Shoe")
                .productPrice(new BigDecimal(unitPrice)).quantity(quantity).build());
        return orderRepository.saveAndFlush(order).getOrderId();
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("select status from orders where order_id = ?", String.class, orderId);
    }

    private long countOrders(String customerId) {
        return jdbc.queryForObject("select count(*) from orders where customer_id = ?", Long.class, customerId);
    }

    private long itemRows(long orderId) {
        return jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long.class, orderId);
    }

    private void stubProduct(long id, String price, int stock) {
        String body = """
                {"id":%d,"sku":"SKU-%d","name":"Trail Shoe %d","price":%s,"stockQuantity":%d,"status":"ACTIVE"}"""
                .formatted(id, id, id, price, stock);
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/" + id)).willReturn(okJson(body)));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/" + id + "/stock")).willReturn(okJson(body)));
    }

    private static void assertDenied(ResponseEntity<String> response, String... mustNotContain) {
        assertThat(response.getStatusCode().value()).as("body: %s", response.getBody()).isIn(401, 403, 404);
        for (String secret : mustNotContain) {
            assertThat(response.getBody()).doesNotContain(secret);
        }
    }

    private static void assertNoStockMovement() {
        PRODUCT_SERVICE.verify(0, patchRequestedFor(urlMatching(".*/stock")));
    }

    /** Records on {@code topic} with {@code key}, read from the beginning over {@code window}. */
    private static List<ConsumerRecord<String, String>> recordsWithKey(String topic, String key, Duration window) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "sec-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    private static String completedJson(long orderId, String amount) {
        return """
                {"paymentId":901,"orderId":%d,"customerId":"x","amount":%s,"transactionId":"tx-%d","completedAt":[2026,10,29,12,30,15,250000000]}"""
                .formatted(orderId, amount, orderId);
    }

    /** Publishes a valid payment.completed for a fresh CONFIRMED order and waits until it is PAID. */
    private void awaitSentinelProcessed(String topic) throws Exception {
        long sentinel = seedOrder(uniq("sentinel"), OrderStatus.CONFIRMED, "10.00", 1);
        publish(topic, Long.toString(sentinel), completedJson(sentinel, "10.00"));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(statusOf(sentinel)).isEqualTo("PAID"));
    }

    // ==== Authorization / IDOR (S-1) =============================================================

    @Test
    void owner_canReadOwnOrder_andAdmin_canReadAndListAnyOrder() {
        String alice = uniq("alice");
        long orderId = seedOrder(alice, OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> own = asCustomer(alice, HttpMethod.GET, "/api/v1/orders/" + orderId, null);
        ResponseEntity<String> admin = as("root", "ADMIN", HttpMethod.GET, "/api/v1/orders/" + orderId, null);
        ResponseEntity<String> adminList = as("root", "ADMIN", HttpMethod.GET, "/api/v1/orders/customer/" + alice, null);

        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(own.getBody()).get("customerId").asString()).isEqualTo(alice);
        assertThat(admin.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(adminList.getBody())).hasSize(1);
    }

    @Test
    void admin_canMoveAPaidOrderToShipped() {
        long orderId = seedOrder(uniq("carol"), OrderStatus.PAID, "20.00", 1);

        ResponseEntity<String> response = as("root", "ADMIN", HttpMethod.PATCH, "/api/v1/orders/" + orderId + "/status?status=SHIPPED", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo("SHIPPED");
    }

    @Test
    void customer_cannotCreateOrderForAnotherCustomer() {
        String alice = uniq("alice");
        String bob = uniq("bob");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/orders?customerId=" + bob, null);

        assertThat(response.getStatusCode().value()).isIn(400, 403);
        assertThat(countOrders(bob)).isZero();
    }

    @Test
    void customer_cannotReadAnotherUsersOrder() {
        String alice = uniq("alice");
        String bob = uniq("bob");
        long bobsOrder = seedOrder(bob, OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.GET, "/api/v1/orders/" + bobsOrder, null);

        assertDenied(response, bob, "Shoe");
    }

    @Test
    void customer_cannotListAnotherCustomersOrders() {
        String alice = uniq("alice");
        String bob = uniq("bob");
        seedOrder(bob, OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.GET, "/api/v1/orders/customer/" + bob, null);

        assertDenied(response, bob, "Shoe");
    }

    @Test
    void customer_cannotCancelAnotherUsersOrder() {
        String alice = uniq("alice");
        long bobsOrder = seedOrder(uniq("bob"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.DELETE, "/api/v1/orders/" + bobsOrder, null);

        assertDenied(response);
        assertThat(statusOf(bobsOrder)).isEqualTo("PENDING");
        assertNoStockMovement();
        assertThat(recordsWithKey("order.cancelled", Long.toString(bobsOrder), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void customer_cannotConfirmAnotherUsersOrder() {
        String alice = uniq("alice");
        long bobsOrder = seedOrder(uniq("bob"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/orders/" + bobsOrder + "/confirm", null);

        assertDenied(response);
        assertThat(statusOf(bobsOrder)).isEqualTo("PENDING");
        assertThat(recordsWithKey("order.placed", Long.toString(bobsOrder), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void customer_cannotAddItemsToAnotherUsersOrder() {
        String alice = uniq("alice");
        stubProduct(42L, "10.00", 100);
        long bobsOrder = seedOrder(uniq("bob"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> viaItems = asCustomer(alice, HttpMethod.POST, "/api/v1/orders/" + bobsOrder + "/items",
                Map.of("productId", 42, "quantity", 3));
        ResponseEntity<String> viaLegacy = asCustomer(alice, HttpMethod.POST,
                "/api/v1/orders/" + bobsOrder + "/products?productId=42&quantity=3", null);

        assertDenied(viaItems);
        assertDenied(viaLegacy);
        assertThat(itemRows(bobsOrder)).isEqualTo(1);
        assertNoStockMovement();
    }

    @Test
    void customer_cannotPatchOrderStatus() {
        String alice = uniq("alice");
        long ownOrder = seedOrder(alice, OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.PATCH, "/api/v1/orders/" + ownOrder + "/status?status=PAID", null);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
        assertThat(statusOf(ownOrder)).isEqualTo("PENDING");
    }

    @Test
    void requestsWithoutIdentityHeaders_areRejected() {
        String bob = uniq("bob");
        long bobsOrder = seedOrder(bob, OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> read = call(HttpMethod.GET, "/api/v1/orders/" + bobsOrder, null);
        ResponseEntity<String> list = call(HttpMethod.GET, "/api/v1/orders/customer/" + bob, null);
        ResponseEntity<String> create = call(HttpMethod.POST, "/api/v1/orders?customerId=" + uniq("ghost"), null);

        assertThat(read.getStatusCode().value()).isEqualTo(401);
        assertThat(list.getStatusCode().value()).isEqualTo(401);
        assertThat(create.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void customer_canCreateReadAddItemsConfirmAndCancelOwnOrder() {
        stubProduct(60L, "10.00", 10);
        String alice = uniq("alice");

        ResponseEntity<String> created = asCustomer(alice, HttpMethod.POST, "/api/v1/orders", null); // customerId defaults to the caller
        long orderId = JSON.readTree(created.getBody()).get("orderId").asLong();
        ResponseEntity<String> added = asCustomer(alice, HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 60, "quantity", 2));
        ResponseEntity<String> legacy = asCustomer(alice, HttpMethod.POST, "/api/v1/orders/" + orderId + "/products?productId=60&quantity=1", null);
        ResponseEntity<String> listed = asCustomer(alice, HttpMethod.GET, "/api/v1/orders/customer/" + alice, null);
        ResponseEntity<String> confirmed = asCustomer(alice, HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null);
        ResponseEntity<String> cancelled = asCustomer(alice, HttpMethod.DELETE, "/api/v1/orders/" + orderId, null);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(JSON.readTree(created.getBody()).get("customerId").asString()).isEqualTo(alice);
        assertThat(added.getStatusCode().value()).isEqualTo(200);
        assertThat(legacy.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(listed.getBody())).hasSize(1);
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void roleComparison_isExact() {
        long orderId = seedOrder(uniq("bob"), OrderStatus.PENDING, "20.00", 1);

        for (String role : new String[]{"admin", "Admin", "ROLE_ADMIN", "ADMINS"}) {
            assertThat(as("root", role, HttpMethod.GET, "/api/v1/orders/" + orderId, null).getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(as("root", role, HttpMethod.PATCH, "/api/v1/orders/" + orderId + "/status?status=CONFIRMED", null).getStatusCode().value()).as(role).isEqualTo(403);
        }
    }

    @Test
    void anonymousAndCustomerCalls_onPatchStatus_are401And403() {
        long orderId = seedOrder(uniq("bob"), OrderStatus.PENDING, "20.00", 1);

        assertThat(call(HttpMethod.PATCH, "/api/v1/orders/" + orderId + "/status?status=CONFIRMED", null).getStatusCode().value()).isEqualTo(401);
        assertThat(asCustomer("bob", HttpMethod.PATCH, "/api/v1/orders/" + orderId + "/status?status=CONFIRMED", null).getStatusCode().value()).isEqualTo(403);
        assertThat(statusOf(orderId)).isEqualTo("PENDING");
    }

    @Test
    void errorBodiesForAuthFailures_areGeneric() {
        long orderId = seedOrder(uniq("bob"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> anonymous = call(HttpMethod.GET, "/api/v1/orders/" + orderId, null);
        ResponseEntity<String> forbidden = asCustomer("alice", HttpMethod.GET, "/api/v1/orders/" + orderId, null);

        assertThat(JSON.readTree(anonymous.getBody()).get("message").asString()).isEqualTo("Authentication required");
        assertThat(JSON.readTree(forbidden.getBody()).get("message").asString()).isEqualTo("Access denied");
    }

    @Test
    void internalEndpoint_returnsTheOrderWithoutIdentity() {
        String owner = uniq("alice");
        long orderId = seedOrder(owner, OrderStatus.CONFIRMED, "20.00", 3);

        ResponseEntity<String> response = call(HttpMethod.GET, "/internal/v1/orders/" + orderId, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode order = JSON.readTree(response.getBody());
        assertThat(order.get("customerId").asString()).isEqualTo(owner);
        assertThat(order.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(call(HttpMethod.GET, "/internal/v1/orders/987654321", null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void apiResponses_carryNosniffAndNoStore() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/v1/orders/hello", null);

        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "2.0", "\"3\"", "1001", "0", "-1", "true", "null"})
    void addItem_quantityMustBeAnIntegerBetween1And1000(String quantity) {
        stubProduct(61L, "10.00", 5000);
        long orderId = seedOrder(uniq("mallory"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = http.post().uri("/api/v1/orders/" + orderId + "/items").headers(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"productId\":61,\"quantity\":" + quantity + "}")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).as("quantity %s: %s", quantity, response.getBody()).isEqualTo(400);
        assertNoStockMovement();
        assertThat(itemRows(orderId)).isEqualTo(1);
    }

    @Test
    void addItem_quantityBounds_1And1000_areAccepted() {
        stubProduct(62L, "1.00", 5000);
        long orderId = seedOrder(uniq("mallory"), OrderStatus.PENDING, "20.00", 1);

        assertThat(callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 62, "quantity", 1)).getStatusCode().value()).isEqualTo(200);
        assertThat(callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 62, "quantity", 1000)).getStatusCode().value()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1001", "0", "abc"})
    void legacyAddProduct_invalidQuantity_is400(String quantity) {
        stubProduct(63L, "10.00", 5000);
        long orderId = seedOrder(uniq("mallory"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/products?productId=63&quantity=" + quantity, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertNoStockMovement();
    }

    @Test
    void addItem_toConfirmedOrder_isRejected_withNoStockMovement() {
        stubProduct(64L, "10.00", 100);
        long orderId = seedOrder(uniq("carol"), OrderStatus.CONFIRMED, "20.00", 1);

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 64, "quantity", 1));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertNoStockMovement();
        assertThat(itemRows(orderId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5.00", "0.00"})
    void addItem_nonPositiveUpstreamPrice_isRefused_withNoStockMovement(String price) {
        stubProduct(65L, price, 100);
        long orderId = seedOrder(uniq("carol"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 65, "quantity", 1));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertNoStockMovement();
        assertThat(itemRows(orderId)).isEqualTo(1);
    }

    @Test
    void adminPatch_toCancelled_restoresStockAndEmitsOrderCancelled() {
        stubProduct(66L, "10.00", 100);
        long orderId = seedOrder(uniq("carol"), OrderStatus.PAID, "20.00", 1);

        ResponseEntity<String> response = as("root", "ADMIN", HttpMethod.PATCH, "/api/v1/orders/" + orderId + "/status?status=CANCELLED", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(recordsWithKey("order.cancelled", Long.toString(orderId), Duration.ofSeconds(5))).hasSize(1);
    }

    @Test
    void downstreamFailureBody_doesNotLeakTheInternalTopology() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/67")).willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(404).withBody("secret-downstream-body")));
        long orderId = seedOrder(uniq("carol"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 67, "quantity", 1));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).doesNotContain("secret-downstream-body", "product-service", "ProductServiceClient", "localhost");
    }

    @Test
    void wrongContentType_is415_andUnacceptable_is406() {
        long orderId = seedOrder(uniq("carol"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> wrongType = http.post().uri("/api/v1/orders/" + orderId + "/items").headers(ADMIN)
                .contentType(MediaType.TEXT_PLAIN).body("productId=1&quantity=1").retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        ResponseEntity<String> unacceptable = http.get().uri("/api/v1/orders/" + orderId).headers(ADMIN).header("Accept", "application/xml")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(wrongType.getStatusCode().value()).isEqualTo(415);
        assertThat(unacceptable.getStatusCode().value()).isEqualTo(406);
    }

    @Test
    void malformedBodyAndWrongTypedParameters_leakNoClassNames() {
        long orderId = seedOrder(uniq("carol"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> body = http.post().uri("/api/v1/orders/" + orderId + "/items").headers(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"productId\":\"abc\",\"quantity\":1}")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        ResponseEntity<String> path = callAsAdmin(HttpMethod.GET, "/api/v1/orders/abc", null);

        for (ResponseEntity<String> r : List.of(body, path)) {
            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(r.getBody()).doesNotContain("com.ecommerce", "java.lang", "tools.jackson", "Exception");
        }
    }

    // ==== Business logic / tampering ===================================================================

    @Test
    void legacyEndpoint_negativeQuantity_cannotCreditStockOrReduceTheTotal() {
        stubProduct(50L, "20.00", 10);
        long orderId = seedOrder(uniq("mallory"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/products?productId=50&quantity=-5", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        PRODUCT_SERVICE.verify(0, patchRequestedFor(urlEqualTo("/internal/v1/products/50/stock")));
        assertThat(itemRows(orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total_amount from orders where order_id = ?", BigDecimal.class, orderId))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void addItem_quantityBeyondStock_isRejected_withNoReservation() {
        stubProduct(51L, "20.00", 10);
        long orderId = seedOrder(uniq("mallory"), OrderStatus.PENDING, "20.00", 1);

        for (int quantity : new int[]{11, Integer.MAX_VALUE}) {
            ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items",
                    Map.of("productId", 51, "quantity", quantity));
            assertThat(response.getStatusCode().value()).as("quantity %d", quantity).isEqualTo(400);
        }

        assertNoStockMovement();
        assertThat(itemRows(orderId)).isEqualTo(1);
    }

    @Test
    void addItem_overflowingQuantityLiteral_isRejected_withNoRowsOrReservation() {
        stubProduct(52L, "20.00", 10);
        long orderId = seedOrder(uniq("mallory"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = http.post().uri("/api/v1/orders/" + orderId + "/items").headers(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"productId\":52,\"quantity\":2147483648}")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertNoStockMovement();
        assertThat(itemRows(orderId)).isEqualTo(1);
    }

    @Test
    void addItem_clientSuppliedPriceAndTotal_areIgnored() {
        stubProduct(53L, "29.99", 10);
        String alice = uniq("alice");
        long orderId = (JSON.readTree(
                asCustomer(alice, HttpMethod.POST, "/api/v1/orders?customerId=" + alice, null).getBody()).get("orderId").asLong());

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of(
                "productId", 53, "quantity", 2, "price", "0.01", "productPrice", "0.01", "totalAmount", "0.01", "status", "PAID"));

        // either the unknown fields are ignored, or the request is refused; never honoured
        assertThat(response.getStatusCode().value()).isIn(200, 400);
        if (response.getStatusCode().value() == 200) {
            assertThat(JSON.readTree(response.getBody()).get("totalAmount").decimalValue()).isEqualByComparingTo("59.98");
        }
        assertThat(jdbc.queryForObject("select total_amount from orders where order_id = ?", BigDecimal.class, orderId))
                .isIn(new BigDecimal("59.9800"), BigDecimal.ZERO.setScale(4));
        assertThat(statusOf(orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForList("select product_price from order_items where order_id = ?", BigDecimal.class, orderId))
                .allSatisfy(price -> assertThat(price).isEqualByComparingTo("29.99"));
    }

    @Test
    void createOrder_serverControlledFieldsInTheBody_areIgnored() {
        String alice = uniq("alice");

        ResponseEntity<String> response = http.post().uri("/api/v1/orders?customerId={c}", alice).headers(h -> { h.set("X-Username", alice); h.set("X-User-Role", "CUSTOMER"); })
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"orderId\":999999999,\"status\":\"PAID\",\"totalAmount\":0.01,\"customerId\":\"bob\",\"orderDate\":\"1999-01-01T00:00:00\"}")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode created = JSON.readTree(response.getBody());
        assertThat(created.get("orderId").asLong()).isNotEqualTo(999999999L);
        assertThat(created.get("customerId").asString()).isEqualTo(alice);
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("totalAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(created.get("orderDate").toString()).doesNotContain("1999");
        assertThat(countOrders(alice)).isEqualTo(1);
        assertThat(countOrders("bob")).isZero();
    }

    @Test
    void patchStatus_skippingStates_isRejected() {
        long pending = seedOrder(uniq("carol"), OrderStatus.PENDING, "20.00", 1);
        long cancelled = seedOrder(uniq("carol"), OrderStatus.CANCELLED, "20.00", 1);

        ResponseEntity<String> toDelivered = as("root", "ADMIN", HttpMethod.PATCH, "/api/v1/orders/" + pending + "/status?status=DELIVERED", null);
        ResponseEntity<String> toPaid = as("root", "ADMIN", HttpMethod.PATCH, "/api/v1/orders/" + cancelled + "/status?status=PAID", null);

        // 409 (spec: an illegal transition conflicts with the current state), not 400
        assertThat(toDelivered.getStatusCode().value()).isEqualTo(409);
        assertThat(toPaid.getStatusCode().value()).isEqualTo(409);
        assertThat(statusOf(pending)).isEqualTo("PENDING");
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void confirmReplay_isRejected_andEmitsOrderPlacedOnce() throws Exception {
        stubProduct(54L, "10.00", 10);
        String alice = uniq("alice");
        long orderId = JSON.readTree(asCustomer(alice, HttpMethod.POST, "/api/v1/orders?customerId=" + alice, null).getBody()).get("orderId").asLong();
        callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 54, "quantity", 1));
        assertThat(callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null).getStatusCode().value()).isEqualTo(200);
        awaitRecord("order.placed", Long.toString(orderId), TIMEOUT);

        ResponseEntity<String> replay = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null);

        assertThat(replay.getStatusCode().value()).isEqualTo(400);
        assertThat(recordsWithKey("order.placed", Long.toString(orderId), Duration.ofSeconds(4))).hasSize(1);
        assertThat(statusOf(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void doubleCancel_isRejected_restoresStockOnceAndEmitsOrderCancelledOnce() {
        stubProduct(55L, "10.00", 10);
        String alice = uniq("alice");
        long orderId = JSON.readTree(asCustomer(alice, HttpMethod.POST, "/api/v1/orders?customerId=" + alice, null).getBody()).get("orderId").asLong();
        callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 55, "quantity", 4));

        ResponseEntity<String> first = callAsAdmin(HttpMethod.DELETE, "/api/v1/orders/" + orderId, null);
        ResponseEntity<String> second = callAsAdmin(HttpMethod.DELETE, "/api/v1/orders/" + orderId, null);

        assertThat(first.getStatusCode().value()).isEqualTo(204);
        assertThat(second.getStatusCode().value()).isEqualTo(400);
        PRODUCT_SERVICE.verify(1, patchRequestedFor(urlEqualTo("/internal/v1/products/55/stock")).withRequestBody(equalToJson("{\"delta\":4}")));
        assertThat(recordsWithKey("order.cancelled", Long.toString(orderId), Duration.ofSeconds(4))).hasSize(1);
    }

    @Test
    void shippedOrder_cannotBeCancelled() {
        long orderId = seedOrder(uniq("carol"), OrderStatus.SHIPPED, "20.00", 1);

        ResponseEntity<String> response = callAsAdmin(HttpMethod.DELETE, "/api/v1/orders/" + orderId, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(statusOf(orderId)).isEqualTo("SHIPPED");
        assertNoStockMovement();
    }

    // ==== Forged / malformed Kafka events =================================================================

    @Test
    void forgedPaymentCompleted_forPendingOrder_doesNotMarkItPaid() throws Exception {
        long pending = seedOrder(uniq("carol"), OrderStatus.PENDING, "500.00", 1);

        publish("payment.completed", Long.toString(pending), completedJson(pending, "500.00"));
        awaitSentinelProcessed("payment.completed");

        assertThat(statusOf(pending)).isEqualTo("PENDING");
    }

    @Test
    void forgedPaymentCompleted_forCancelledOrder_doesNotResurrectIt() throws Exception {
        long cancelled = seedOrder(uniq("carol"), OrderStatus.CANCELLED, "500.00", 1);

        publish("payment.completed", Long.toString(cancelled), completedJson(cancelled, "500.00"));
        awaitSentinelProcessed("payment.completed");

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void forgedPaymentCompleted_withMismatchedAmount_doesNotMarkOrderPaid() throws Exception {
        long confirmed = seedOrder(uniq("carol"), OrderStatus.CONFIRMED, "500.00", 1);

        publish("payment.completed", Long.toString(confirmed), completedJson(confirmed, "0.01"));
        awaitSentinelProcessed("payment.completed");

        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
    }

    @Test
    void forgedPaymentFailed_forPaidOrder_doesNotCancelIt() throws Exception {
        long paid = seedOrder(uniq("carol"), OrderStatus.PAID, "500.00", 1);
        long shipped = seedOrder(uniq("carol"), OrderStatus.SHIPPED, "500.00", 1);

        for (long id : new long[]{paid, shipped}) {
            publish("payment.failed", Long.toString(id),
                    "{\"paymentId\":1,\"orderId\":%d,\"customerId\":\"x\",\"failureReason\":\"forged\",\"failedAt\":[2026,10,29,12,31]}".formatted(id));
        }
        awaitSentinelProcessed("payment.completed");
        // payment.failed is another topic/consumer: give it a moment, then check
        Thread.sleep(3000);

        assertThat(statusOf(paid)).isEqualTo("PAID");
        assertThat(statusOf(shipped)).isEqualTo("SHIPPED");
    }

    @Test
    void replayedPaymentCompleted_isIdempotent() throws Exception {
        long confirmed = seedOrder(uniq("carol"), OrderStatus.CONFIRMED, "20.00", 1);

        publish("payment.completed", Long.toString(confirmed), completedJson(confirmed, "20.00"));
        publish("payment.completed", Long.toString(confirmed), completedJson(confirmed, "20.00"));
        awaitSentinelProcessed("payment.completed");

        assertThat(statusOf(confirmed)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long.class, confirmed)).isEqualTo(1);
    }

    @Test
    void malformedOversizedAndUnknownEvents_neverBlockTheConsumer() throws Exception {
        String huge = "x".repeat(900_000);
        List<String> poison = List.of(
                "not json at all",
                "",
                "[1,2,3]",
                "\"just a string\"",
                "{}",
                "{\"orderId\":null}",
                "{\"orderId\":\"abc\"}",
                "{\"orderId\":99999999999999999999999}",
                "{\"orderId\":-1,\"amount\":-5}",
                "{\"type\":\"SOMETHING_ELSE\",\"orderId\":1,\"unknown\":{\"nested\":[1,2,3]}}",
                "{\"orderId\":1,\"customerId\":\"" + huge + "\"}");
        for (String payload : poison) {
            publish("payment.completed", "poison", payload);
            publish("payment.failed", "poison", payload);
        }

        awaitSentinelProcessed("payment.completed");

        // the group's committed offset caught up with the log end: nothing is being retried in a loop
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets("order-service")
                        .partitionsToOffsetAndMetadata().get();
                var ends = admin.listOffsets(Map.of(
                        new TopicPartition("payment.completed", 0), org.apache.kafka.clients.admin.OffsetSpec.latest(),
                        new TopicPartition("payment.failed", 0), org.apache.kafka.clients.admin.OffsetSpec.latest())).all().get();
                ends.forEach((tp, end) -> assertThat(committed.get(tp)).as("group has committed on %s", tp).isNotNull().extracting(OffsetAndMetadata::offset).as("committed offset of %s", tp).isEqualTo(end.offset()));
            });
        }
    }

    // ==== Injection & data ===================================================================================

    static final String[] SQLI = {
            "' OR '1'='1", "' OR 1=1 --", "bob' --", "'; DROP TABLE orders; --", "\" OR \"\"=\"", "%", "_ob", "1; SELECT pg_sleep(5)--",
            "' UNION SELECT NULL,NULL,NULL,NULL,NULL,NULL,NULL --"
    };

    @Test
    void sqlInjectionInCustomerPath_returnsNothing_andLeavesTablesIntact() {
        String bob = uniq("bob");
        seedOrder(bob, OrderStatus.PENDING, "20.00", 1);
        long ordersBefore = jdbc.queryForObject("select count(*) from orders", Long.class);

        for (String payload : SQLI) {
            ResponseEntity<String> response = http.get().uri("/api/v1/orders/customer/{c}", payload).headers(ADMIN)
                    .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
            assertThat(response.getStatusCode().value()).as(payload).isEqualTo(200);
            assertThat(JSON.readTree(response.getBody())).as("result for %s", payload).isEmpty();
        }

        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(ordersBefore);
        assertThat(jdbc.queryForObject("select to_regclass('public.orders') is not null and to_regclass('public.order_items') is not null", Boolean.class)).isTrue();
        assertThat(countOrders(bob)).isEqualTo(1);
    }

    @Test
    void sqlInjectionAndXssInCustomerId_areStoredAsPlainData_andReturnedAsJson() {
        List<String> payloads = new ArrayList<>(List.of(SQLI));
        payloads.add("<script>alert(1)</script>");
        payloads.add("\"><img src=x onerror=alert(document.cookie)>");
        payloads.add("</script><svg/onload=alert(1)>");
        payloads.add("javascript:alert(1)");
        long ordersBefore = jdbc.queryForObject("select count(*) from orders", Long.class);

        for (String raw : payloads) {
            // suffix keeps each stored value unique, so it cannot collide with the SQLi probes on the read path
            String payload = raw + " " + uniq("u");
            ResponseEntity<String> response = http.post().uri("/api/v1/orders?customerId={c}", payload).headers(ADMIN)
                    .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
            assertThat(response.getStatusCode().value()).as(payload).isEqualTo(201);
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).as("content type for %s", payload).isTrue();
            assertThat(JSON.readTree(response.getBody()).get("customerId").asString()).isEqualTo(payload);
            assertThat(countOrders(payload)).as("row for %s", payload).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(ordersBefore + payloads.size());
        assertThat(jdbc.queryForObject("select to_regclass('public.orders') is not null", Boolean.class)).isTrue();
    }

    @Test
    void customerId_withNulByteOrOverlong_isA4xx_notAServerError() {
        for (String bad : new String[]{"a\u0000b", "x".repeat(300)}) {
            ResponseEntity<String> response = http.post().uri("/api/v1/orders?customerId={c}", bad).headers(ADMIN)
                    .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

            assertThat(response.getStatusCode().is4xxClientError()).as("status for %d chars: %s", bad.length(), response.getBody()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders/1%20OR%201=1", "/api/v1/orders/-1", "/api/v1/orders/0", "/api/v1/orders/99999999999999999999",
            "/api/v1/orders/%00", "/api/v1/orders/%27", "/api/v1/orders/1%2F..%2F..%2Factuator", "/api/v1/orders/customer/%00"})
    void hostilePathProbes_neverProduceAServerError(String path) {
        ResponseEntity<String> response = http.get().uri(URI.create("http://localhost:" + port + path))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).as("%s -> %s", path, response.getBody()).isLessThan(500);
    }

    @Test
    void patchStatus_withInjectionInStatus_changesNothing() {
        long orderId = seedOrder(uniq("carol"), OrderStatus.PENDING, "20.00", 1);

        ResponseEntity<String> response = http.patch().uri("/api/v1/orders/{id}/status?status={s}", orderId, "PAID' WHERE 1=1; --")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(statusOf(orderId)).isEqualTo("PENDING");
    }

    // ==== Error bodies ===========================================================================================

    @Test
    void unknownPath_is404() {
        ResponseEntity<String> response = callAsAdmin(HttpMethod.GET, "/api/v1/orders/1/nope", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void errorResponses_areJson_withoutStackTracesOrSql() {
        long orderId = seedOrder(uniq("carol"), OrderStatus.CANCELLED, "20.00", 1);
        List<ResponseEntity<String>> errors = List.of(
                callAsAdmin(HttpMethod.GET, "/api/v1/orders/987654321", null),
                callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null),
                callAsAdmin(HttpMethod.DELETE, "/api/v1/orders/" + orderId, null),
                callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items", Map.of("productId", 1, "quantity", 0)),
                callAsAdmin(HttpMethod.PUT, "/api/v1/orders/" + orderId, null));

        for (ResponseEntity<String> error : errors) {
            assertThat(error.getStatusCode().is4xxClientError()).as(error.getBody()).isTrue();
            assertThat(error.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(error.getBody()).doesNotContain("\tat ", "Exception", "org.springframework", "org.hibernate", "select ", "SQLState", "trace");
        }
    }
}

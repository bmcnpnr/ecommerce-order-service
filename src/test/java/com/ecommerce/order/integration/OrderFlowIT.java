package com.ecommerce.order.integration;

import com.ecommerce.order.config.KafkaProducerConfig;
import com.ecommerce.order.event.OrderPlacedEvent;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through HTTP: create order, add an item (product-service stubbed),
 * place it, then check the rows in Postgres and the record on {@code order.placed};
 * plus cancellation (stock restore + {@code order.cancelled}) and a downstream 404.
 */
class OrderFlowIT extends ApplicationTestBase {

    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(30);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orderRepository;

    private final ObjectMapper json = new ObjectMapper();

    private static String productJson(long id, String price, int stock) {
        return """
                {"id":%d,"sku":"SKU-%d","name":"Trail Shoe %d","price":%s,"stockQuantity":%d,"brand":"ACME","status":"ACTIVE"}
                """.formatted(id, id, id, price, stock);
    }

    private void stubProduct(long id, String price, int stock) {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/" + id)).willReturn(okJson(productJson(id, price, stock))));
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/" + id + "/stock")).willReturn(okJson(productJson(id, price, stock))));
    }

    private long createOrder(String customerId) {
        ResponseEntity<String> created = callAsAdmin(HttpMethod.POST, "/api/v1/orders?customerId=" + customerId, null);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return json.readTree(created.getBody()).get("orderId").asLong();
    }

    private JsonNode addItem(long orderId, long productId, int quantity) {
        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items",
                Map.of("productId", productId, "quantity", quantity));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return json.readTree(response.getBody());
    }

    @Test
    void createAddItemPlace_persistsOrderAndPublishesOrderPlaced() {
        stubProduct(42L, "29.99", 10);

        long orderId = createOrder("cust-flow-1");
        JsonNode afterAdd = addItem(orderId, 42L, 2);
        ResponseEntity<String> placed = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null);

        // HTTP
        assertThat(afterAdd.get("totalAmount").decimalValue()).isEqualByComparingTo("59.98");
        assertThat(afterAdd.get("orderItems").get(0).get("productName").asString()).isEqualTo("Trail Shoe 42");
        assertThat(placed.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(placed.getBody()).get("status").asString()).isEqualTo("CONFIRMED");

        // product-service saw the stock reservation
        PRODUCT_SERVICE.verify(1, patchRequestedFor(urlEqualTo("/internal/v1/products/42/stock"))
                .withRequestBody(equalToJson("{\"delta\":-2}")));

        // Postgres
        Map<String, Object> orderRow = jdbc.queryForMap(
                "select customer_id, status, total_amount from orders where order_id = ?", orderId);
        assertThat(orderRow.get("customer_id")).isEqualTo("cust-flow-1");
        assertThat(orderRow.get("status")).isEqualTo("CONFIRMED");
        assertThat((BigDecimal) orderRow.get("total_amount")).isEqualByComparingTo("59.98");
        Map<String, Object> itemRow = jdbc.queryForMap(
                "select product_id, product_name, product_price, quantity from order_items where order_id = ?", orderId);
        assertThat(itemRow.get("product_id")).isEqualTo(42L);
        assertThat(itemRow.get("product_name")).isEqualTo("Trail Shoe 42");
        assertThat((BigDecimal) itemRow.get("product_price")).isEqualByComparingTo("29.99");
        assertThat(itemRow.get("quantity")).isEqualTo(2);

        // Kafka: key, shape, and exactly what the configured serializer makes of the expected event
        ConsumerRecord<String, String> record = awaitRecord("order.placed", Long.toString(orderId), KAFKA_TIMEOUT);
        JsonNode payload = json.readTree(record.value());
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payload.get("customerId").asString()).isEqualTo("cust-flow-1");
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("59.98");
        assertThat(payload.get("orderDate").isArray()).as("LocalDateTime on the wire is an int array").isTrue();
        assertThat(payload.get("items")).hasSize(1);
        assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(42L);
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(record.headers().toArray()).as("no type headers").isEmpty();

        Order stored = orderRepository.findById(orderId).orElseThrow();
        OrderPlacedEvent expected = OrderPlacedEvent.builder()
                .orderId(orderId).customerId("cust-flow-1").totalAmount(stored.getTotalAmount())
                .orderDate(stored.getOrderDate())
                .items(stored.getOrderItems().stream().map(i -> OrderPlacedEvent.OrderItemEvent.builder()
                        .productId(i.getProductId()).productName(i.getProductName())
                        .productPrice(i.getProductPrice()).quantity(i.getQuantity()).build()).toList())
                .build();
        assertThat(payload).isEqualTo(json.readTree(KafkaProducerConfig.eventJsonMapper().writeValueAsString(expected)));
    }

    @Test
    void cancelPlacedOrder_restoresStockAndPublishesOrderCancelled() {
        stubProduct(43L, "5.00", 10);
        long orderId = createOrder("cust-flow-2");
        addItem(orderId, 43L, 3);
        callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null);

        ResponseEntity<String> cancelled = callAsAdmin(HttpMethod.DELETE, "/api/v1/orders/" + orderId, null);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select status from orders where order_id = ?", String.class, orderId))
                .isEqualTo("CANCELLED");
        PRODUCT_SERVICE.verify(1, patchRequestedFor(urlEqualTo("/internal/v1/products/43/stock"))
                .withRequestBody(equalToJson("{\"delta\":3}")));

        JsonNode payload = json.readTree(awaitRecord("order.cancelled", Long.toString(orderId), KAFKA_TIMEOUT).value());
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payload.get("customerId").asString()).isEqualTo("cust-flow-2");
        assertThat(payload.get("cancelledAt").isArray()).isTrue();

        // A cancelled order cannot be placed again.
        ResponseEntity<String> replace = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", null);
        assertThat(replace.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownProduct_is404AndLeavesOrderUntouched() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo("/api/v1/products/999")).willReturn(aResponse().withStatus(404)));
        long orderId = createOrder("cust-flow-3");

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items",
                Map.of("productId", 999, "quantity", 1));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(json.readTree(response.getBody()).get("message").asString()).contains("Downstream service call failed");
        PRODUCT_SERVICE.verify(0, patchRequestedFor(urlMatching(".*/stock")));
        assertThat(jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long.class, orderId)).isZero();
    }

    @Test
    void insufficientStock_is400AndReservesNothing() {
        stubProduct(44L, "5.00", 1);
        long orderId = createOrder("cust-flow-4");

        ResponseEntity<String> response = callAsAdmin(HttpMethod.POST, "/api/v1/orders/" + orderId + "/items",
                Map.of("productId", 44, "quantity", 2));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(json.readTree(response.getBody()).get("message").asString())
                .isEqualTo("Insufficient stock for product SKU-44. Available: 1");
        PRODUCT_SERVICE.verify(0, patchRequestedFor(urlMatching(".*/stock")));
    }

    @Test
    void unknownOrder_is404() {
        ResponseEntity<String> response = callAsAdmin(HttpMethod.GET, "/api/v1/orders/987654321", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(json.readTree(response.getBody()).get("correlationId").asString())
                .isEqualTo(response.getHeaders().getFirst("X-Correlation-Id"));
    }
}

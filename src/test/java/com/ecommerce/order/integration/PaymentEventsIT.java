package com.ecommerce.order.integration;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * payment-service events arriving on the real broker are picked up by the production
 * listener container (StringDeserializer + JacksonJsonMessageConverter) and move the
 * order in Postgres. Payloads are written the way payment-service serializes them:
 * no type headers, java.time as int arrays.
 */
class PaymentEventsIT extends ApplicationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired private OrderRepository orderRepository;

    private long confirmedOrder(String customerId) {
        return confirmedOrder(customerId, 1L, 1);
    }

    private long confirmedOrder(String customerId, long productId, int quantity) {
        Order order = Order.builder().customerId(customerId).status(OrderStatus.CONFIRMED)
                .orderDate(LocalDateTime.now()).totalAmount(BigDecimal.ZERO).build();
        order.addItem(OrderItem.builder().productId(productId).productName("Shoe")
                .productPrice(new BigDecimal("20.00")).quantity(quantity).build());
        return orderRepository.saveAndFlush(order).getOrderId();
    }

    private static String failedJson(long orderId) {
        return """
                {"paymentId":902,"orderId":%d,"customerId":"cust-pay","failureReason":"Card declined","failedAt":[2026,10,29,12,31]}
                """.formatted(orderId);
    }

    private static void stubStock(long productId) {
        PRODUCT_SERVICE.stubFor(patch(urlEqualTo("/internal/v1/products/" + productId + "/stock")).willReturn(okJson("{\"id\":" + productId + "}")));
    }

    private static int stockRequests(long productId) {
        return PRODUCT_SERVICE.findAll(patchRequestedFor(urlEqualTo("/internal/v1/products/" + productId + "/stock"))).size();
    }

    private OrderStatus statusOf(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private static String completedJson(long orderId) {
        return """
                {"paymentId":901,"orderId":%d,"customerId":"cust-pay","amount":20.00,"transactionId":"tx-%d","completedAt":[2026,10,29,12,30,15,250000000]}
                """.formatted(orderId, orderId);
    }

    @Test
    void paymentCompleted_marksOrderPaid() throws Exception {
        long orderId = confirmedOrder("cust-pay-1");

        publish("payment.completed", Long.toString(orderId), completedJson(orderId));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID));
    }

    @Test
    void paymentFailed_cancelsOrder_andReturnsItsStock() throws Exception {
        stubStock(1L);
        long orderId = confirmedOrder("cust-pay-2");

        publish("payment.failed", Long.toString(orderId), failedJson(orderId));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED));
        // the stock goes back right after the commit, outside the transaction; wait for it so it cannot leak into the next test
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stockRequests(1L)).isEqualTo(1));
        PRODUCT_SERVICE.verify(1, patchRequestedFor(urlEqualTo("/internal/v1/products/1/stock")).withRequestBody(equalToJson("{\"delta\":1}")));
    }

    /**
     * I-4: a declined payment used to cancel the order and leave its stock reserved for ever. Delivery is at least
     * once, so the same event arrives again; the stock must go back exactly once. The listener handles one partition in
     * order, so when the sentinel order (behind the three copies) is cancelled, the copies have been handled.
     */
    @Test
    void paymentFailed_returnsTheReservedStockExactlyOnce_alsoWhenTheEventIsReplayed() throws Exception {
        stubStock(71L);
        stubStock(72L);
        long orderId = confirmedOrder("cust-pay-4", 71L, 3);
        long sentinel = confirmedOrder("cust-pay-4-sentinel", 72L, 1);

        publish("payment.failed", Long.toString(orderId), failedJson(orderId));
        publish("payment.failed", Long.toString(orderId), failedJson(orderId));
        publish("payment.failed", Long.toString(orderId), failedJson(orderId));
        publish("payment.failed", Long.toString(sentinel), failedJson(sentinel));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(sentinel)).isEqualTo(OrderStatus.CANCELLED));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stockRequests(72L)).isEqualTo(1));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockRequests(71L)).as("stock requests for the order whose payment.failed arrived three times").isEqualTo(1);
        PRODUCT_SERVICE.verify(1, patchRequestedFor(urlEqualTo("/internal/v1/products/71/stock")).withRequestBody(equalToJson("{\"delta\":3}")));
        // order.cancelled keeps meaning "somebody asked for a cancel": not published for a failed payment
        assertThatThrownBy(() -> awaitRecord("order.cancelled", Long.toString(orderId), Duration.ofSeconds(3)))
                .isInstanceOf(AssertionError.class);
    }

    /** A late payment.failed for an order the customer already cancelled must not give its stock back a second time. */
    @Test
    void paymentFailed_forAnOrderTheCustomerAlreadyCancelled_returnsNothing() throws Exception {
        stubStock(73L);
        stubStock(74L);
        long cancelled = confirmedOrder("cust-pay-5", 73L, 2);
        Order order = orderRepository.findById(cancelled).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.saveAndFlush(order);
        long sentinel = confirmedOrder("cust-pay-5-sentinel", 74L, 1);

        publish("payment.failed", Long.toString(cancelled), failedJson(cancelled));
        publish("payment.failed", Long.toString(sentinel), failedJson(sentinel));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(sentinel)).isEqualTo(OrderStatus.CANCELLED));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stockRequests(74L)).isEqualTo(1));
        assertThat(stockRequests(73L)).isZero();
    }

    @Test
    void paymentForUnknownOrder_isSkippedAndListenerKeepsConsuming() throws Exception {
        long orderId = confirmedOrder("cust-pay-3");

        publish("payment.completed", "987654321", completedJson(987654321L));
        publish("payment.completed", Long.toString(orderId), completedJson(orderId));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID));
        assertThat(orderRepository.findById(987654321L)).isEmpty();
    }
}

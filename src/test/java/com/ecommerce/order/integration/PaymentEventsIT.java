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

import static org.assertj.core.api.Assertions.assertThat;
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
        Order order = Order.builder().customerId(customerId).status(OrderStatus.CONFIRMED)
                .orderDate(LocalDateTime.now()).totalAmount(BigDecimal.ZERO).build();
        order.addItem(OrderItem.builder().productId(1L).productName("Shoe")
                .productPrice(new BigDecimal("20.00")).quantity(1).build());
        return orderRepository.saveAndFlush(order).getOrderId();
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
    void paymentFailed_cancelsOrder() throws Exception {
        long orderId = confirmedOrder("cust-pay-2");

        publish("payment.failed", Long.toString(orderId), """
                {"paymentId":902,"orderId":%d,"customerId":"cust-pay","failureReason":"Card declined","failedAt":[2026,10,29,12,31]}
                """.formatted(orderId));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED));
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

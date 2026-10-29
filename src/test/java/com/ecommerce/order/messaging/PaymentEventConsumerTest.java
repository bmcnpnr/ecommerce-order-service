package com.ecommerce.order.messaging;

import com.ecommerce.order.event.PaymentCompletedEvent;
import com.ecommerce.order.event.PaymentFailedEvent;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock private OrderRepository orderRepository;

    @InjectMocks private PaymentEventConsumer consumer;

    private Order order;

    @BeforeEach
    void setUp() {
        order = Order.builder().orderId(5L).customerId("user1").status(OrderStatus.CONFIRMED)
                .totalAmount(new BigDecimal("20.00")).orderItems(new ArrayList<>()).build();
    }

    private static PaymentCompletedEvent completed(long orderId) {
        return PaymentCompletedEvent.builder().paymentId(1L).orderId(orderId).customerId("user1")
                .amount(new BigDecimal("20.00")).transactionId("tx-1").completedAt(LocalDateTime.now()).build();
    }

    private static PaymentFailedEvent failed(long orderId) {
        return PaymentFailedEvent.builder().paymentId(1L).orderId(orderId).customerId("user1")
                .failureReason("card declined").failedAt(LocalDateTime.now()).build();
    }

    @Test
    void paymentCompleted_marksOrderPaid() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        consumer.handlePaymentCompleted(completed(5L));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).save(order);
    }

    @Test
    void paymentCompleted_unknownOrder_isIgnored() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        consumer.handlePaymentCompleted(completed(99L));

        verify(orderRepository, never()).save(any());
    }

    @Test
    void paymentFailed_cancelsOrder() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        consumer.handlePaymentFailed(failed(5L));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
    }

    @Test
    void paymentFailed_unknownOrder_isIgnored() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        consumer.handlePaymentFailed(failed(99L));

        verify(orderRepository, never()).save(any());
    }

    @Test
    void paymentCompleted_amountMismatch_isIgnored() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        PaymentCompletedEvent event = completed(5L);
        event.setAmount(new BigDecimal("0.01"));

        consumer.handlePaymentCompleted(event);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void paymentCompleted_sameAmountWithDifferentScale_marksPaid() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        PaymentCompletedEvent event = completed(5L);
        event.setAmount(new BigDecimal("20.0000"));

        consumer.handlePaymentCompleted(event);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"})
    void paymentCompleted_forAnOrderThatIsNotConfirmed_isIgnored(OrderStatus status) {
        order.setStatus(status);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        consumer.handlePaymentCompleted(completed(5L));

        assertThat(order.getStatus()).isEqualTo(status);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void paymentCompleted_withoutOrderIdOrAmount_isIgnoredWithoutAnException() {
        consumer.handlePaymentCompleted(new PaymentCompletedEvent());
        consumer.handlePaymentCompleted(PaymentCompletedEvent.builder().orderId(5L).build());

        verify(orderRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "DELIVERED", "CANCELLED"})
    void paymentFailed_forAnOrderPastConfirmation_isIgnored(OrderStatus status) {
        order.setStatus(status);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        consumer.handlePaymentFailed(failed(5L));

        assertThat(order.getStatus()).isEqualTo(status);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void paymentFailed_cancelsPendingOrder() {
        order.setStatus(OrderStatus.PENDING);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        consumer.handlePaymentFailed(failed(5L));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void paymentFailed_withoutOrderId_isIgnored() {
        consumer.handlePaymentFailed(new PaymentFailedEvent());

        verify(orderRepository, never()).findById(any());
    }
}

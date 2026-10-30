package com.ecommerce.order.messaging;

import com.ecommerce.order.event.PaymentCompletedEvent;
import com.ecommerce.order.event.PaymentFailedEvent;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.order.service.OrderService.PaymentFailureOutcome;
import com.ecommerce.order.service.OrderService.PaymentFailureResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderService orderService;

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

    // ---- idempotency and log volume (K2): a duplicate changes nothing and costs at most one line ----

    @Test
    void aDuplicatePaymentCompleted_changesNothing_andAFloodOfThemWritesAtMostOneLine() {
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger consumerLogger = (Logger) LoggerFactory.getLogger(PaymentEventConsumer.class);
        consumerLogger.addAppender(logs);
        try {
            for (int i = 0; i < 500; i++) {
                consumer.handlePaymentCompleted(completed(5L));
            }
        } finally {
            consumerLogger.detachAppender(logs);
        }

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository, never()).save(any());
        assertThat(logs.list).hasSizeLessThanOrEqualTo(1);
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    // ---- payment.failed: delegated to OrderService.cancelAfterFailedPayment (cancel + give the stock back) ----

    private ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(PaymentEventConsumer.class)).addAppender(logs);
        return logs;
    }

    private void release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(PaymentEventConsumer.class)).detachAppender(logs);
    }

    @Test
    void paymentFailed_isHandedToTheService_whichDoesTheWork() {
        when(orderService.cancelAfterFailedPayment(5L))
                .thenReturn(new PaymentFailureResult(PaymentFailureOutcome.CANCELLED, OrderStatus.CANCELLED));

        consumer.handlePaymentFailed(failed(5L));

        verify(orderService).cancelAfterFailedPayment(5L);
        verifyNoInteractions(orderRepository);
    }

    /** The service returns the reserved stock after its transaction commits, so the listener must not wrap it in one. */
    @Test
    void handlePaymentFailed_isNotTransactional() throws Exception {
        assertThat(PaymentEventConsumer.class.getMethod("handlePaymentFailed", PaymentFailedEvent.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    @Test
    void aReplayedPaymentFailed_isOneInfoLine_andTheServiceHasAlreadyReturnedNothing() {
        when(orderService.cancelAfterFailedPayment(5L))
                .thenReturn(new PaymentFailureResult(PaymentFailureOutcome.ALREADY_CANCELLED, OrderStatus.CANCELLED));
        ListAppender<ILoggingEvent> logs = capture();
        try {
            for (int i = 0; i < 500; i++) {
                consumer.handlePaymentFailed(failed(5L));
            }
        } finally {
            release(logs);
        }

        verify(orderService, times(500)).cancelAfterFailedPayment(5L);
        assertThat(logs.list).hasSizeLessThanOrEqualTo(1);
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "DELIVERED"})
    void paymentFailed_forAnOrderPastConfirmation_isWarnedAbout(OrderStatus status) {
        when(orderService.cancelAfterFailedPayment(5L))
                .thenReturn(new PaymentFailureResult(PaymentFailureOutcome.NOT_APPLICABLE, status));
        ListAppender<ILoggingEvent> logs = capture();
        try {
            consumer.handlePaymentFailed(failed(5L));
        } finally {
            release(logs);
        }

        assertThat(logs.list).hasSizeLessThanOrEqualTo(1);
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void paymentFailed_withoutOrderId_isIgnored_andDoesNotCallTheService() {
        consumer.handlePaymentFailed(new PaymentFailedEvent());
        consumer.handlePaymentFailed(null);

        verifyNoInteractions(orderService, orderRepository);
    }

    @Test
    void aFloodOfForgedPaymentFailedForUnknownOrders_writesAtMostOneLine() {
        when(orderService.cancelAfterFailedPayment(98765L))
                .thenReturn(new PaymentFailureResult(PaymentFailureOutcome.UNKNOWN_ORDER, null));
        ListAppender<ILoggingEvent> logs = capture();
        try {
            for (int i = 0; i < 500; i++) {
                consumer.handlePaymentFailed(failed(98765L));
            }
        } finally {
            release(logs);
        }

        assertThat(logs.list).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void aVersionConflictFromTheService_isNotSwallowed_soTheListenerRetries() {
        when(orderService.cancelAfterFailedPayment(5L))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Order.class, 5L));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.handlePaymentFailed(failed(5L)))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }
}

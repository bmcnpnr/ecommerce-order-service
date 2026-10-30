package com.ecommerce.order.messaging;

import com.ecommerce.order.event.PaymentCompletedEvent;
import com.ecommerce.order.event.PaymentFailedEvent;
import com.ecommerce.order.logging.LogThrottle;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * Applies payment results to orders. Delivery is at least once and the producer's outbox can publish
 * twice, so every handler is guarded by the order's state: an event that no longer applies changes
 * nothing and costs one INFO line. Lines are throttled because Kafka has no authentication, so a flood of
 * forged or replayed events must not become a flood of log lines.
 *
 * <p>{@code payment.failed} is not handled here but by {@link OrderService#cancelAfterFailedPayment}: cancelling
 * also gives the reserved stock back, which calls product-service and so must run outside any database
 * transaction. That is why this method is not {@code @Transactional}.
 *
 * <p>An exception here is the error handler's business ({@code KafkaErrorHandling}): infrastructure
 * trouble is retried with a growing pause, anything else ends up on {@code <topic>.DLT}.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final LogThrottle EVENT_LOG = new LogThrottle(10, TimeUnit.SECONDS);

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @KafkaListener(topics = "payment.completed", groupId = "order-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        if (event == null || event.getOrderId() == null || event.getAmount() == null) {
            warn("completed.invalid", "Ignoring payment.completed without orderId or amount");
            return;
        }
        orderRepository.findById(event.getOrderId()).ifPresentOrElse(
            order -> {
                if (order.getStatus() != OrderStatus.CONFIRMED) {
                    switch (order.getStatus()) {
                        // Already applied (redelivery) or the order moved on: nothing to do.
                        case PAID, SHIPPED, DELIVERED -> info("completed.applied",
                                "payment.completed for order {} already applied (status {})", order.getOrderId(), order.getStatus());
                        // Money was taken for an order that cannot take it: somebody has to refund.
                        default -> warn("completed.unapplicable",
                                "payment.completed for order {} in status {} was not applied; the payment may need a refund",
                                order.getOrderId(), order.getStatus());
                    }
                    return;
                }
                if (order.getTotalAmount() == null || order.getTotalAmount().compareTo(event.getAmount()) != 0) {
                    warn("completed.amount", "Ignoring payment.completed for order {}: amount {} does not match the order total {}",
                            order.getOrderId(), event.getAmount(), order.getTotalAmount());
                    return;
                }
                order.setStatus(OrderStatus.PAID);
                orderRepository.save(order);
                log.info("Order {} status updated to PAID", event.getOrderId());
            },
            () -> warn("completed.unknown", "Order not found for payment.completed event: orderId={}", event.getOrderId())
        );
    }

    @KafkaListener(topics = "payment.failed", groupId = "order-service")
    public void handlePaymentFailed(PaymentFailedEvent event) {
        if (event == null || event.getOrderId() == null) {
            warn("failed.invalid", "Ignoring payment.failed without orderId");
            return;
        }
        OrderService.PaymentFailureResult result = orderService.cancelAfterFailedPayment(event.getOrderId());
        switch (result.outcome()) {
            case CANCELLED -> { } // logged by the service, which also returned the stock
            case ALREADY_CANCELLED -> info("failed.applied", "payment.failed for order {} already applied", event.getOrderId());
            case NOT_APPLICABLE -> warn("failed.unapplicable", "Ignoring payment.failed for order {} in status {}",
                    event.getOrderId(), result.status());
            case UNKNOWN_ORDER -> warn("failed.unknown", "Order not found for payment.failed event: orderId={}", event.getOrderId());
        }
    }

    private static void info(String key, String message, Object... args) {
        long suppressed = EVENT_LOG.acquire(key);
        if (suppressed >= 0) {
            log.info(message + LogThrottle.suppressedSuffix(suppressed), args);
        }
    }

    private static void warn(String key, String message, Object... args) {
        long suppressed = EVENT_LOG.acquire(key);
        if (suppressed >= 0) {
            log.warn(message + LogThrottle.suppressedSuffix(suppressed), args);
        }
    }
}

package com.ecommerce.order.messaging;

import com.ecommerce.order.event.PaymentCompletedEvent;
import com.ecommerce.order.event.PaymentFailedEvent;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final OrderRepository orderRepository;

    @KafkaListener(topics = "payment.completed", groupId = "order-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received payment.completed for orderId: {}", event.getOrderId());
        orderRepository.findById(event.getOrderId()).ifPresentOrElse(
            order -> {
                order.setStatus(OrderStatus.PAID);
                orderRepository.save(order);
                log.info("Order {} status updated to PAID", event.getOrderId());
            },
            () -> log.warn("Order not found for paymentCompleted event: orderId={}", event.getOrderId())
        );
    }

    @KafkaListener(topics = "payment.failed", groupId = "order-service")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("Received payment.failed for orderId: {}", event.getOrderId());
        orderRepository.findById(event.getOrderId()).ifPresentOrElse(
            order -> {
                order.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(order);
                log.info("Order {} status updated to CANCELLED due to payment failure", event.getOrderId());
            },
            () -> log.warn("Order not found for paymentFailed event: orderId={}", event.getOrderId())
        );
    }
}

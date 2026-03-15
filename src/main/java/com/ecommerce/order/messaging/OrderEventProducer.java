package com.ecommerce.order.messaging;

import com.ecommerce.order.event.OrderCancelledEvent;
import com.ecommerce.order.event.OrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);
    private static final String ORDER_PLACED_TOPIC = "order.placed";
    private static final String ORDER_CANCELLED_TOPIC = "order.cancelled";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderPlaced(OrderPlacedEvent event) {
        log.info("Publishing order.placed event for orderId: {}", event.getOrderId());
        kafkaTemplate.send(ORDER_PLACED_TOPIC, event.getOrderId().toString(), event);
    }

    public void publishOrderCancelled(OrderCancelledEvent event) {
        log.info("Publishing order.cancelled event for orderId: {}", event.getOrderId());
        kafkaTemplate.send(ORDER_CANCELLED_TOPIC, event.getOrderId().toString(), event);
    }
}

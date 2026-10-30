package com.ecommerce.order.messaging;

import com.ecommerce.order.config.KafkaProducerConfig;
import com.ecommerce.order.event.OrderCancelledEvent;
import com.ecommerce.order.event.OrderPlacedEvent;
import com.ecommerce.order.outbox.OutboxEvent;
import com.ecommerce.order.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

/**
 * Announces order events. The record is not sent here: it is written to the outbox table in the
 * caller's transaction, so it exists exactly when the order change commits, and
 * {@link com.ecommerce.order.outbox.OutboxRelay} publishes it to Kafka afterwards (R-2). The JSON is the same
 * the old {@code JacksonJsonSerializer} wrote: java.time as numeric arrays, no type headers.
 */
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);
    private static final String ORDER_PLACED_TOPIC = "order.placed";
    private static final String ORDER_CANCELLED_TOPIC = "order.cancelled";
    private static final JsonMapper EVENT_MAPPER = KafkaProducerConfig.eventJsonMapper();

    private final OutboxEventRepository outbox;

    @Transactional
    public void publishOrderPlaced(OrderPlacedEvent event) {
        enqueue(ORDER_PLACED_TOPIC, event.getOrderId().toString(), event);
    }

    @Transactional
    public void publishOrderCancelled(OrderCancelledEvent event) {
        enqueue(ORDER_CANCELLED_TOPIC, event.getOrderId().toString(), event);
    }

    private void enqueue(String topic, String key, Object event) {
        outbox.save(OutboxEvent.builder()
                .topic(topic)
                .eventKey(key)
                .payload(EVENT_MAPPER.writeValueAsString(event))
                .createdAt(LocalDateTime.now())
                .build());
        log.debug("Queued {} for orderId {}", topic, key);
    }
}

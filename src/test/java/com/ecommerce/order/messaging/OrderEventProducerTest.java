package com.ecommerce.order.messaging;

import com.ecommerce.order.config.KafkaProducerConfig;
import com.ecommerce.order.event.OrderCancelledEvent;
import com.ecommerce.order.event.OrderPlacedEvent;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventProducerTest {

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks private OrderEventProducer producer;

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 29, 14, 5, 30, 123_000_000);

    private static OrderPlacedEvent placedEvent() {
        return OrderPlacedEvent.builder()
                .orderId(42L).customerId("user1").totalAmount(new BigDecimal("59.9800")).orderDate(TIME)
                .items(List.of(OrderPlacedEvent.OrderItemEvent.builder()
                        .productId(7L).productName("Shoe").productPrice(new BigDecimal("29.99")).quantity(2).build()))
                .build();
    }

    @Test
    void publishOrderPlaced_sendsToOrderPlacedTopicKeyedByOrderId() {
        OrderPlacedEvent event = placedEvent();

        producer.publishOrderPlaced(event);

        verify(kafkaTemplate).send("order.placed", "42", event);
    }

    @Test
    void publishOrderCancelled_sendsToOrderCancelledTopicKeyedByOrderId() {
        OrderCancelledEvent event = OrderCancelledEvent.builder().orderId(42L).customerId("user1").cancelledAt(TIME).build();

        producer.publishOrderCancelled(event);

        verify(kafkaTemplate).send("order.cancelled", "42", event);
    }

    /**
     * The payload the template is handed is serialized by the value serializer on the
     * production ProducerFactory. Consumers (and the Pact contracts) pin java.time as
     * the numeric array form, and no type headers may be added.
     */
    @Test
    @SuppressWarnings("unchecked")
    void configuredValueSerializer_writesDatesAsArraysAndNoTypeInfo() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:0");
        Serializer<Object> serializer = (Serializer<Object>)
                ((DefaultKafkaProducerFactory<String, Object>) config.producerFactory()).getValueSerializer();
        var headers = new org.apache.kafka.common.header.internals.RecordHeaders();

        byte[] bytes = serializer.serialize("order.placed", headers, placedEvent());
        JsonNode json = new ObjectMapper().readTree(bytes);

        assertThat(headers.toArray()).isEmpty();
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
        assertThat(json.get("customerId").asString()).isEqualTo("user1");
        assertThat(json.get("totalAmount").decimalValue()).isEqualByComparingTo("59.98");
        assertThat(json.get("orderDate").isArray()).isTrue();
        assertThat(json.get("orderDate").toString()).isEqualTo("[2026,10,29,14,5,30,123000000]");
        JsonNode item = json.get("items").get(0);
        assertThat(item.get("productId").asLong()).isEqualTo(7L);
        assertThat(item.get("productName").asString()).isEqualTo("Shoe");
        assertThat(item.get("productPrice").decimalValue()).isEqualByComparingTo("29.99");
        assertThat(item.get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredValueSerializer_writesCancelledAtAsArray() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:0");
        Serializer<Object> serializer = (Serializer<Object>)
                ((DefaultKafkaProducerFactory<String, Object>) config.producerFactory()).getValueSerializer();

        byte[] bytes = serializer.serialize("order.cancelled",
                OrderCancelledEvent.builder().orderId(42L).customerId("user1").cancelledAt(TIME.withNano(0)).build());
        JsonNode json = new ObjectMapper().readTree(bytes);

        assertThat(json.get("cancelledAt").toString()).isEqualTo("[2026,10,29,14,5,30]");
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
    }
}

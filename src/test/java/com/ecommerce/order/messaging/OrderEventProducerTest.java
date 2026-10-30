package com.ecommerce.order.messaging;

import com.ecommerce.order.config.KafkaProducerConfig;
import com.ecommerce.order.event.OrderCancelledEvent;
import com.ecommerce.order.event.OrderPlacedEvent;
import com.ecommerce.order.outbox.OutboxEvent;
import com.ecommerce.order.outbox.OutboxEventRepository;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The producer no longer talks to Kafka: it writes the record to the outbox table in the caller's
 * transaction and OutboxRelay publishes it. What must not change is the record itself: topic, key and the
 * exact JSON bytes consumers and the Pact contracts depend on.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventProducerTest {

    @Mock private OutboxEventRepository outbox;

    @InjectMocks private OrderEventProducer producer;

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 29, 14, 5, 30, 123_000_000);

    private static OrderPlacedEvent placedEvent() {
        return OrderPlacedEvent.builder()
                .orderId(42L).customerId("user1").totalAmount(new BigDecimal("59.9800")).orderDate(TIME)
                .items(List.of(OrderPlacedEvent.OrderItemEvent.builder()
                        .productId(7L).productName("Shoe").productPrice(new BigDecimal("29.99")).quantity(2).build()))
                .build();
    }

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        verifyNoMoreInteractions(outbox);
        return captor.getValue();
    }

    @Test
    void publishOrderPlaced_queuesARecordForOrderPlacedKeyedByOrderId() {
        producer.publishOrderPlaced(placedEvent());

        OutboxEvent row = saved();
        assertThat(row.getTopic()).isEqualTo("order.placed");
        assertThat(row.getEventKey()).isEqualTo("42");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getParkedAt()).isNull();
    }

    @Test
    void publishOrderCancelled_queuesARecordForOrderCancelledKeyedByOrderId() {
        producer.publishOrderCancelled(OrderCancelledEvent.builder().orderId(42L).customerId("user1").cancelledAt(TIME).build());

        OutboxEvent row = saved();
        assertThat(row.getTopic()).isEqualTo("order.cancelled");
        assertThat(row.getEventKey()).isEqualTo("42");
    }

    /** java.time as numeric arrays, BigDecimal as a number: what every consumer and the Pact contracts pin. */
    @Test
    void payload_writesDatesAsArrays() {
        producer.publishOrderPlaced(placedEvent());

        JsonNode json = new ObjectMapper().readTree(saved().getPayload());

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
    void payload_writesCancelledAtAsArray() {
        producer.publishOrderCancelled(OrderCancelledEvent.builder().orderId(42L).customerId("user1").cancelledAt(TIME.withNano(0)).build());

        JsonNode json = new ObjectMapper().readTree(saved().getPayload());

        assertThat(json.get("cancelledAt").toString()).isEqualTo("[2026,10,29,14,5,30]");
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
    }

    /** The outbox row holds exactly the bytes the JacksonJsonSerializer producer used to put on the topic. */
    @Test
    void payload_isByteForByteWhatTheOldSerializerWrote() {
        OrderPlacedEvent event = placedEvent();
        JacksonJsonSerializer<Object> legacy = new JacksonJsonSerializer<>(KafkaProducerConfig.eventJsonMapper()).noTypeInfo();
        RecordHeaders headers = new RecordHeaders();

        producer.publishOrderPlaced(event);

        assertThat(saved().getPayload().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(legacy.serialize("order.placed", headers, event));
        assertThat(headers.toArray()).as("no type headers").isEmpty();
    }
}

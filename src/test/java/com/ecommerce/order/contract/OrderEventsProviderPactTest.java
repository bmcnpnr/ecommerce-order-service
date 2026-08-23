package com.ecommerce.order.contract;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider side of the Kafka contracts for the {@code order.*} events.
 *
 * <p>notification-service holds a pact describing the {@code order.placed} and
 * {@code order.cancelled} records it can consume (copy in
 * {@code src/test/resources/pacts/}, see {@code ecommerce-platform/sync-pacts.sh}).
 * For each interaction this class produces the record the way production does: the
 * real {@link OrderService} builds the event from the order, the real
 * {@code OrderEventProducer} hands it to the {@code KafkaTemplate} (mocked here to
 * capture topic, key and payload), and the payload is serialized with the value
 * serializer configured on the production {@link ProducerFactory} (spring-kafka's
 * {@code JsonSerializer}, no type headers). Pact then matches bytes and metadata
 * against the consumer's expectations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Provider("order-service")
@PactFolder("pacts")
class OrderEventsProviderPactTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProducerFactory<String, Object> producerFactory;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private ProductServiceClient productServiceClient; // cancelOrder restores stock through it; not under test here

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ───────────────────────── provider states ─────────────────────────
    // State names are part of the contract: consumers reference them verbatim.

    @State("order 7 is pending with items")
    void order7PendingWithItems() {
        stubOrder(OrderStatus.PENDING);
    }

    @State("order 7 is confirmed")
    void order7Confirmed() {
        stubOrder(OrderStatus.CONFIRMED);
    }

    // ───────────────────────── message producers ─────────────────────────
    // The annotation value must equal the consumer's expectsToReceive(...) description.

    @PactVerifyProvider("an order.placed event")
    MessageAndMetadata orderPlaced() {
        orderService.confirmOrder(7L);
        return capturedRecord("order.placed");
    }

    @PactVerifyProvider("an order.cancelled event")
    MessageAndMetadata orderCancelled() {
        orderService.cancelOrder(7L);
        return capturedRecord("order.cancelled");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void stubOrder(OrderStatus status) {
        reset(orderRepository);
        reset(kafkaTemplate);
        when(orderRepository.findById(7L)).thenAnswer(inv -> Optional.of(order(status)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The (topic, key, value) the production code handed to KafkaTemplate, serialized exactly as the producer would. */
    private MessageAndMetadata capturedRecord(String expectedTopic) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(expectedTopic), key.capture(), value.capture());
        byte[] bytes = valueSerializer().serialize(expectedTopic, new RecordHeaders(), value.getValue());
        return new MessageAndMetadata(bytes, Map.of(
                "contentType", "application/json",
                "kafka_topic", expectedTopic,
                "kafka_key", key.getValue()));
    }

    /** Instantiates and configures the value serializer class exactly as the Kafka client would from the production ProducerFactory. */
    @SuppressWarnings("unchecked")
    private Serializer<Object> valueSerializer() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();
        Object configured = config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        try {
            Class<?> type = configured instanceof Class<?> c ? c : Class.forName(String.valueOf(configured));
            Serializer<Object> serializer = (Serializer<Object>) type.getDeclaredConstructor().newInstance();
            serializer.configure(config, false);
            return serializer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate configured value serializer " + configured, e);
        }
    }

    private static Order order(OrderStatus status) {
        Order order = Order.builder()
                .orderId(7L)
                .customerId("customer-42")
                .orderDate(LocalDateTime.now())
                .status(status)
                .totalAmount(BigDecimal.ZERO)
                .orderItems(new ArrayList<>())
                .build();
        order.addItem(OrderItem.builder().orderItemId(70L).productId(1L).productName("Wireless Mouse")
                .productPrice(new BigDecimal("49.99")).quantity(2).build());
        return order;
    }
}

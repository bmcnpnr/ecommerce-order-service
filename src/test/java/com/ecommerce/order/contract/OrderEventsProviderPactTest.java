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
import com.ecommerce.order.outbox.OutboxEvent;
import com.ecommerce.order.outbox.OutboxEventRepository;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * {@code OrderEventProducer} serializes it and writes it to the outbox table (the repository is
 * mocked here to capture topic, key and payload; {@code OutboxRelay} sends exactly these bytes
 * to Kafka). Pact then matches bytes and metadata against the consumer's expectations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Provider("order-service")
@PactFolder("pacts")
class OrderEventsProviderPactTest {

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private ProductServiceClient productServiceClient; // cancelOrder restores stock through it; not under test here

    @MockitoBean
    private OutboxEventRepository outboxRepository;

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
        reset(outboxRepository);
        when(orderRepository.findById(7L)).thenAnswer(inv -> Optional.of(order(status)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The (topic, key, value) the production code wrote to the outbox: the bytes OutboxRelay will send. */
    private MessageAndMetadata capturedRecord(String expectedTopic) {
        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(row.capture());
        assertThat(row.getValue().getTopic()).isEqualTo(expectedTopic);
        return new MessageAndMetadata(row.getValue().getPayload().getBytes(StandardCharsets.UTF_8), Map.of(
                "contentType", "application/json",
                "kafka_topic", expectedTopic,
                "kafka_key", row.getValue().getEventKey()));
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

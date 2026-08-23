package com.ecommerce.order.contract;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.LambdaDsl;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ecommerce.order.contract.KafkaPactDsl.EXAMPLE_TIME;
import static com.ecommerce.order.contract.KafkaPactDsl.localDateTime;
import static com.ecommerce.order.contract.KafkaPactDsl.timestampArrays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer side of the Kafka contract between order-service and payment-service.
 *
 * <p>order-service listens on {@code payment.completed} and {@code payment.failed}
 * ({@link com.ecommerce.order.messaging.PaymentEventConsumer}) and moves the order to
 * PAID / CANCELLED. Each pact below is the message shape order-service can handle; the
 * test publishes exactly that message to an embedded Kafka broker and lets the
 * <em>production</em> consumer path — {@code KafkaProducerConfig}'s listener container
 * factory, {@code StringDeserializer} + {@code JsonMessageConverter}, the
 * {@code @KafkaListener} method, the repository call — process it.
 *
 * <p>order-service only <em>reads</em> {@code orderId}, but it deserializes the whole
 * record into {@code PaymentCompletedEvent}/{@code PaymentFailedEvent}, so a type change
 * in <em>any</em> field it declares would break the consumer; every declared field is
 * therefore part of the contract with a type matcher.
 *
 * <p>Running this class writes {@code target/pacts/order-service-payment-service.json},
 * which is copied into payment-service's {@code src/test/resources/pacts/} (see
 * {@code ecommerce-platform/sync-pacts.sh}) and verified there by
 * {@code PaymentEventsProviderPactTest} against the real producer code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"payment.completed", "payment.failed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-service", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class PaymentEventsConsumerPactTest {

    static final String CONSUMER = "order-service";

    @MockitoBean
    private OrderRepository orderRepository;

    @Autowired
    private EmbeddedKafkaBroker broker;

    // ───────────────────────────── pacts ─────────────────────────────

    @Pact(consumer = CONSUMER)
    V4Pact paymentCompleted(MessagePactBuilder builder) {
        return builder
                .given("payment 501 for order 7 has completed")
                .expectsToReceive("a payment.completed event")
                .withMetadata(md -> md
                        .add("contentType", "application/json")
                        .add("kafka_topic", "payment.completed")
                        // record key = orderId
                        .matchRegex("kafka_key", "\\d+", "7"))
                .withContent(timestampArrays(LambdaDsl.newJsonBody(event -> {
                    event.integerType("paymentId", 501)
                            .integerType("orderId", 7)
                            .stringType("customerId", "customer-42")
                            .numberType("amount", 99.98)
                            .stringType("transactionId", "TXN-1A2B3C4D");
                    localDateTime(event, "completedAt", EXAMPLE_TIME);
                }).build(), "completedAt"))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact paymentFailed(MessagePactBuilder builder) {
        return builder
                .given("payment 502 for order 7 has failed")
                .expectsToReceive("a payment.failed event")
                .withMetadata(md -> md
                        .add("contentType", "application/json")
                        .add("kafka_topic", "payment.failed")
                        .matchRegex("kafka_key", "\\d+", "7"))
                .withContent(timestampArrays(LambdaDsl.newJsonBody(event -> {
                    event.integerType("paymentId", 502)
                            .integerType("orderId", 7)
                            .stringType("customerId", "customer-42")
                            .stringType("failureReason", "Payment declined by gateway");
                    localDateTime(event, "failedAt", EXAMPLE_TIME);
                }).build(), "failedAt"))
                .toPact(V4Pact.class);
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    @PactTestFor(pactMethod = "paymentCompleted")
    void paymentCompleted_marksTheOrderPaid(List<V4Interaction.AsynchronousMessage> messages) {
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order(7L, OrderStatus.CONFIRMED)));

        publish(messages.get(0));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, timeout(30_000)).save(saved.capture());
        assertThat(saved.getValue().getOrderId()).isEqualTo(7L);
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @PactTestFor(pactMethod = "paymentFailed")
    void paymentFailed_cancelsTheOrder(List<V4Interaction.AsynchronousMessage> messages) {
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order(7L, OrderStatus.CONFIRMED)));

        publish(messages.get(0));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, timeout(30_000)).save(saved.capture());
        assertThat(saved.getValue().getOrderId()).isEqualTo(7L);
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    /** Publishes the pact's example message as raw bytes on the topic named in its metadata — no type headers, exactly like the producers. */
    private void publish(V4Interaction.AsynchronousMessage message) {
        String topic = String.valueOf(message.getMetadata().get("kafka_topic"));
        String key = String.valueOf(message.getMetadata().get("kafka_key"));
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic, key, message.contentsAsBytes()));
            producer.flush();
        }
    }

    private static Order order(long id, OrderStatus status) {
        return Order.builder().orderId(id).customerId("customer-42").status(status)
                .totalAmount(new BigDecimal("99.98")).orderItems(new ArrayList<>()).build();
    }
}

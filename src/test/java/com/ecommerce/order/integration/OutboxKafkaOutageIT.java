package com.ecommerce.order.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.outbox.OutboxRelay;
import com.ecommerce.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The headline promise of the outbox: with Kafka unreachable, an order can still be confirmed, promptly;
 * the event waits in the table, the relay backs off and stays quiet, and the event is published once
 * (not lost, not twice) when the broker comes back.
 *
 * <p>The broker is frozen with {@code docker pause} and thawed afterwards. It is this class's own
 * Kafka (and Postgres), so the other integration tests, which share one broker, never see the outage.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class OutboxKafkaOutageIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void thaw() {
        try {
            DockerClientFactory.lazyClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // not paused: fine
        }
    }

    @LocalServerPort int port;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;

    private ResponseEntity<String> confirm(long orderId) {
        return RestClient.create("http://localhost:" + port).post().uri("/api/v1/orders/" + orderId + "/confirm")
                .headers(h -> {
                    h.set("X-Username", "root");
                    h.set("X-User-Role", "ADMIN");
                })
                .retrieve().onStatus(HttpStatusCode::isError, (rq, rs) -> { }).toEntity(String.class);
    }

    private List<ConsumerRecord<String, String>> recordsWithKey(String topic, String key, Duration window) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outage-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    @Test
    void withKafkaUnreachable_confirmStillReturnsPromptly_theEventWaits_andIsPublishedOnceWhenKafkaIsBack() {
        Order order = Order.builder().customerId("cust-outage").status(OrderStatus.PENDING).orderDate(LocalDateTime.now())
                .totalAmount(BigDecimal.ZERO).build();
        order.addItem(OrderItem.builder().productId(1L).productName("Shoe").productPrice(new BigDecimal("20.00")).quantity(1).build());
        long orderId = orders.saveAndFlush(order).getOrderId();
        String key = Long.toString(orderId);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        relayLogger.addAppender(logs);
        // spring-kafka's default producer listener logs an ERROR with a stack trace for every failed send
        ListAppender<ILoggingEvent> producerLogs = new ListAppender<>();
        producerLogs.start();
        Logger producerLogger = (Logger) LoggerFactory.getLogger(LoggingProducerListener.class);
        producerLogger.addAppender(producerLogs);
        try {
            DockerClientFactory.lazyClient().pauseContainerCmd(KAFKA.getContainerId()).exec();

            long start = System.nanoTime();
            ResponseEntity<String> confirmed = confirm(orderId);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // the request does not wait for Kafka (it used to block for max.block.ms = 60 s)
            assertThat(confirmed.getStatusCode().value()).as(confirmed.getBody()).isEqualTo(200);
            assertThat(elapsedMs).as("confirm while Kafka is down").isLessThan(3000);
            assertThat(jdbc.queryForObject("select status from orders where order_id = ?", String.class, orderId)).isEqualTo("CONFIRMED");

            // the relay keeps trying (each attempt gives up after max.block.ms = 5 s) and the row stays
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select attempts from outbox_event where event_key = ?", Integer.class, key)).isGreaterThanOrEqualTo(2));
            assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select last_error from outbox_event where event_key = ?", String.class, key)).contains("Exception");
            List<ILoggingEvent> loud = logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
            assertThat(loud).as("relay lines while Kafka is down: one WARN, no stack trace").hasSize(1);
            assertThat(loud.get(0).getThrowableProxy()).isNull();
            assertThat(loud.get(0).getFormattedMessage()).contains("order.placed").contains("retrying in");
            assertThat(producerLogs.list).as("the relay reports for itself; the producer listener adds nothing").isEmpty();

            DockerClientFactory.lazyClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();

            await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key)).isZero());
            List<ConsumerRecord<String, String>> published = recordsWithKey("order.placed", key, Duration.ofSeconds(5));
            assertThat(published).as("published exactly once").hasSize(1);
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.INFO)
                    .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("resumed after").contains("failed attempts"));
        } finally {
            relayLogger.detachAppender(logs);
            producerLogger.detachAppender(producerLogs);
            thaw();
        }
    }
}

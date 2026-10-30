package com.ecommerce.order.integration;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Postgres and Kafka shared by every *IT class: the containers are static and
 * started once per JVM (singleton pattern; Ryuk removes them at exit), so all
 * integration test classes and Spring contexts reuse the same pair. Images match
 * what the platform deploys.
 *
 * <p>Postgres is wired with {@code @ServiceConnection}. Kafka cannot be: this service
 * uses plain spring-kafka without Boot's Kafka auto-configuration (there is no
 * consumer for {@code KafkaConnectionDetails}), and {@code KafkaProducerConfig} reads
 * {@code spring.kafka.bootstrap-servers} itself, so that property is set directly.
 *
 * <p>{@code disabledWithoutDocker}: without a Docker daemon the IT classes are skipped
 * rather than failed. The containers start in the static initializer, which runs only
 * once a class is actually executed (after that condition has been evaluated).
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class ContainersBase {

    static final List<String> TOPICS = List.of("order.placed", "order.cancelled", "payment.completed", "payment.failed");

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
        // Create the topics up front so the listener containers get partitions assigned
        // immediately instead of waiting on a metadata refresh after auto-creation.
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create Kafka topics", e);
        }
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static KafkaProducer<String, String> rawProducer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }

    static void publish(String topic, String key, String json) throws Exception {
        try (KafkaProducer<String, String> producer = rawProducer()) {
            producer.send(new ProducerRecord<>(topic, key, json)).get();
        }
    }

    /** Reads {@code topic} from the beginning (fresh group) until a record with {@code key} arrives. */
    static ConsumerRecord<String, String> awaitRecord(String topic, String key, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic + " within " + timeout);
    }
}

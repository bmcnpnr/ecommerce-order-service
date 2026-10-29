package com.ecommerce.order.integration;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

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
}

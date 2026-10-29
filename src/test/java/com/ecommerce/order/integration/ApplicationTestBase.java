package com.ecommerce.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Full application on a random port against the shared Postgres/Kafka containers,
 * with product-service played by WireMock: the real Feign client, load balancer
 * (simple discovery instead of Eureka), decoder and error handling are exercised.
 * All subclasses share one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
abstract class ApplicationTestBase extends ContainersBase {

    static final WireMockServer PRODUCT_SERVICE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PRODUCT_SERVICE.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PRODUCT_SERVICE::stop));
    }

    @DynamicPropertySource
    static void productServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri",
                PRODUCT_SERVICE::baseUrl);
    }

    @LocalServerPort
    int port;

    RestClient http;

    @BeforeEach
    void setUpHttp() {
        PRODUCT_SERVICE.resetAll();
        http = RestClient.create("http://localhost:" + port);
    }

    ResponseEntity<String> call(HttpMethod method, String path, Object jsonBody) {
        var spec = http.method(method).uri(path);
        if (jsonBody != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        return spec.retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
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

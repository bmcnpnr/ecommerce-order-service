package com.ecommerce.order.config;

import com.ecommerce.order.kafka.DeadLetterRecoverer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Strings in, strings out: events are serialised to JSON when they are written to the outbox table
     * ({@link #eventJsonMapper()}), and the relay and the dead-letter publisher only move those bytes.
     * Used by {@link com.ecommerce.order.outbox.OutboxRelay} and the dead-letter recoverer.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        reconnectBackoff(configs);
        // A dead broker must not hold a request or listener thread for the 60 s default: give up after 5 s
        // (metadata) / 10 s (one request) / 30 s (whole delivery) and let the caller back off.
        configs.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        configs.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        configs.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configs, new StringSerializer(), new StringSerializer());
    }

    /**
     * Event JSON as it has always looked on the wire: spring-kafka's mapper defaults,
     * but with java.time as numeric arrays ([y, M, d, H, m, s, nanos]) instead of the
     * ISO strings Jackson 3 writes by default. Every consumer and the Pact contracts
     * pin the array form.
     */
    public static JsonMapper eventJsonMapper() {
        return JacksonMapperUtils.enhancedJsonMapper().rebuild()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory());
        // The relay and the dead-letter recoverer report a failed send themselves, throttled and without a
        // stack trace; the default listener would log an ERROR with one per failed send.
        template.setProducerListener(new ProducerListener<>() { });
        return template;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        reconnectBackoff(configs);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    /** {@code payment.completed} to {@code payment.completed.DLT}, partition left to the producer. */
    @Bean(destroyMethod = "close")
    public DeadLetterRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate,
                                                   ObjectProvider<MeterRegistry> meters) {
        return new DeadLetterRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1), meters.getIfAvailable());
    }

    /** Retry and dead-letter policy for every listener: see {@link KafkaErrorHandling}. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterRecoverer recoverer) {
        return KafkaErrorHandling.errorHandlerFor(recoverer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    /** A dead broker then costs one "could not be established" WARN per client every ~10 s, not one per second. */
    private static void reconnectBackoff(Map<String, Object> configs) {
        configs.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000);
        configs.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
    }
}

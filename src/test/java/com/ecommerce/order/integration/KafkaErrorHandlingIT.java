package com.ecommerce.order.integration;

import com.ecommerce.order.config.KafkaErrorHandling;
import com.ecommerce.order.config.KafkaProducerConfig;
import com.ecommerce.order.event.PaymentCompletedEvent;
import com.ecommerce.order.kafka.DeadLetterRecoverer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a listener container built by {@link KafkaProducerConfig} does with records that fail (R-1, K1), on a
 * real broker. A scratch topic and consumer group keep this apart from the order-service listeners; the
 * container factory, converter, error handler and dead-letter recoverer are the production ones.
 *
 * <p>The log assertions are the point of this class: the incident this guards against was a single bad
 * record producing a stack trace per poll. Every scenario also publishes a sentinel record behind the bad one,
 * which must still be processed: a bad record never blocks its partition.
 */
@SpringJUnitConfig(classes = {KafkaProducerConfig.class, KafkaErrorHandlingIT.FlakyListener.class})
class KafkaErrorHandlingIT extends ContainersBase {

    static final String TOPIC = "error-handling-it." + UUID.randomUUID().toString().substring(0, 8);
    static final String DLT = TOPIC + ".DLT";

    @DynamicPropertySource
    static void scratchTopic(DynamicPropertyRegistry registry) {
        registry.add("error.handling.it.topic", () -> TOPIC);
        registry.add("error.handling.it.group", () -> "group-" + TOPIC);
    }

    /** A listener whose behaviour each test sets: how it fails for which order. */
    static class FlakyListener {
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Set<String> processed = ConcurrentHashMap.newKeySet();
        volatile Function<AtomicInteger, RuntimeException> failure = attempt -> null;

        @KafkaListener(topics = "${error.handling.it.topic}", groupId = "${error.handling.it.group}")
        public void on(PaymentCompletedEvent event) {
            String order = String.valueOf(event.getOrderId());
            AtomicInteger attempt = attempts.computeIfAbsent(order, k -> new AtomicInteger());
            attempt.incrementAndGet();
            RuntimeException toThrow = failure.apply(attempt);
            if (toThrow != null && !order.startsWith("9")) { // the sentinels (order ids 9xxx) always succeed
                throw toThrow;
            }
            processed.add(order);
        }
    }

    @Autowired private FlakyListener listener;

    @BeforeEach
    void healthyListener() {
        listener.failure = attempt -> null;
    }

    private static String event(long orderId) {
        return """
                {"paymentId":1,"orderId":%d,"customerId":"c","amount":10.00,"transactionId":"tx","completedAt":[2026,10,29,12,30,15]}"""
                .formatted(orderId);
    }

    private static void send(String topic, String key, String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(), ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 4_000_000),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    /** The first record with {@code key} on {@code topic}, read from the start with a throw-away group. */
    private static Optional<ConsumerRecord<String, String>> findRecord(String topic, String key, Duration wait) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "find-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long end = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < end) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        return Optional.of(r);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void awaitProcessed(String order) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(listener.processed).contains(order));
    }

    @Test
    void aRecordThatCannotBeConverted_goesToTheDltUnchanged_costsAHandfulOfLines_andNeverBlocksTheTopic() throws Exception {
        String badKey = "bad-" + UUID.randomUUID();
        try (LogCapture logs = new LogCapture()) {
            send(TOPIC, badKey, "this is not json at all");
            send(TOPIC, "sentinel", event(9001));

            awaitProcessed("9001");

            ConsumerRecord<String, String> deadLetter = findRecord(DLT, badKey, Duration.ofSeconds(30)).orElseThrow();
            assertThat(deadLetter.value()).isEqualTo("this is not json at all");
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-exception-stacktrace")).as("it is what pushes a near-limit record over the size limit").isNull();
            // let the container poll for a while: a record stuck in a loop would keep writing
            Thread.sleep(3000);
            assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(2);
            assertThat(logs.stackTraceLines()).isZero();
        }
    }

    @Test
    void aRecordNearTheSizeLimit_isDeadLetteredOnce_andTheTopicMovesOn() throws Exception {
        String hugeKey = "huge-" + UUID.randomUUID();
        try (LogCapture logs = new LogCapture()) {
            // just under the broker's 1 MiB limit: it can be produced, but its dead-letter copy with the exception
            // headers used to be too large to publish, so it was skipped (or, before that, retried at poll rate)
            send(TOPIC, hugeKey, "x".repeat(1_047_000));
            send(TOPIC, "sentinel", event(9002));

            awaitProcessed("9002");
            Thread.sleep(3000);

            assertThat(findRecord(DLT, hugeKey, Duration.ofSeconds(30))).isPresent()
                    .get().satisfies(r -> assertThat(r.value()).hasSize(1_047_000));
            assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(2);
            assertThat(logs.stackTraceLines()).isZero();
        }
    }

    /** Three failures in a row would have exhausted the "2 retries then dead-letter" policy; infrastructure trouble outlasts it. */
    @Test
    void whileTheDatabaseIsDown_theRecordIsRetriedWithGrowingPauses_andNeverDeadLettered() throws Exception {
        listener.failure = attempt -> attempt.get() <= 3 ? new DataAccessResourceFailureException("database is down") : null;
        long start = System.nanoTime();
        try (LogCapture logs = new LogCapture()) {
            send(TOPIC, "db-down", event(101));

            awaitProcessed("101");

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(listener.attempts.get("101").get()).isEqualTo(4);
            assertThat(elapsedMs).as("pauses of 1 s, 2 s and 4 s between the four attempts").isGreaterThanOrEqualTo(6500);
            assertThat(findRecord(DLT, "db-down", Duration.ofSeconds(3))).isEmpty();
            assertThat(logs.problems()).as("one line per window, not per attempt").hasSizeLessThanOrEqualTo(2);
            assertThat(logs.stackTraceLines()).isZero();
        }
    }

    @Test
    void aListenerBug_getsThreeAttemptsOneSecondApart_thenTheDlt() throws Exception {
        listener.failure = attempt -> new IllegalStateException("bug in the listener");
        long start = System.nanoTime();
        try (LogCapture logs = new LogCapture()) {
            send(TOPIC, "buggy", event(102));
            send(TOPIC, "sentinel", event(9003));

            ConsumerRecord<String, String> deadLetter = findRecord(DLT, "buggy", Duration.ofSeconds(30)).orElseThrow();
            awaitProcessed("9003");

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(deadLetter.value()).isEqualTo(event(102));
            assertThat(listener.attempts.get("102").get()).isEqualTo(3);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(2000);
            // the failing kind once, with its stack trace (ListenerFailureLogger), and the dead letter once
            assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(3);
            assertThat(logs.problems()).anySatisfy(line -> assertThat(line.getFormattedMessage()).contains("Listener failed for").contains("bug in the listener"));
        }
    }

    // ---- dead-lettering that itself fails ------------------------------------------------------------------

    @Test
    void aRecordThatCanNeverBeDeadLettered_isSkippedWithOneErrorLine_andTheSentinelBehindItIsStillProcessed() throws Exception {
        String topic = newTopic();
        @SuppressWarnings("unchecked")
        KafkaOperations<String, String> deadLetters = mock(KafkaOperations.class);
        when(deadLetters.send(any(ProducerRecord.class)))
                .thenAnswer(i -> CompletableFuture.failedFuture(new InvalidTopicException(topic + ".DLT")));
        List<String> processed = new CopyOnWriteArrayList<>();

        try (LogCapture logs = new LogCapture()) {
            ConcurrentMessageListenerContainer<String, String> container = startContainer(topic, deadLetters, processed);
            try {
                send(topic, "poison", "this is not json at all");
                send(topic, "sentinel", event(9004));

                await().atMost(Duration.ofSeconds(60)).until(() -> processed.contains("9004"));
                Thread.sleep(3000);

                // the old wiring published the same record again and again, about twice a second
                verify(deadLetters, times(1)).send(any(ProducerRecord.class));
                assertThat(logs.problems()).as(logs.describe()).hasSize(1);
                assertThat(logs.problems().get(0).getFormattedMessage())
                        .contains("cannot be written to " + topic + ".DLT").contains("skipping it so the partition keeps moving");
                assertThat(logs.stackTraceLines()).isZero();
            } finally {
                container.stop();
            }
        }
    }

    @Test
    void whenTheBrokerDoesNotAnswerForAWhile_deadLetteringIsRetriedWithPauses_notInATightLoop() throws Exception {
        String topic = newTopic();
        @SuppressWarnings("unchecked")
        KafkaOperations<String, String> deadLetters = mock(KafkaOperations.class);
        when(deadLetters.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Topic not present in metadata after 5000 ms")))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Topic not present in metadata after 5000 ms")))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
        List<String> processed = new CopyOnWriteArrayList<>();

        try (LogCapture logs = new LogCapture()) {
            ConcurrentMessageListenerContainer<String, String> container = startContainer(topic, deadLetters, processed);
            try {
                send(topic, "poison", "this is not json at all");
                send(topic, "sentinel", event(9005));

                await().atMost(Duration.ofSeconds(60)).until(() -> processed.contains("9005"));

                verify(deadLetters, times(3)).send(any(ProducerRecord.class));
                // one throttled WARN for the retries and one for the dead letter; no stack trace
                assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(2);
                assertThat(logs.stackTraceLines()).isZero();
            } finally {
                container.stop();
            }
        }
    }

    /**
     * A record that fit its own topic, but not once the exception details travel with it: the real producer refuses
     * the copy ({@code max.request.size} shrunk so the test does not need a megabyte) and the recoverer sends it bare.
     */
    @Test
    void aRecordThatNoLongerFitsWithItsExceptionDetails_isDeadLetteredBare_onceAndQuietly() throws Exception {
        String topic = newTopic();
        Map<String, Object> small = new HashMap<>();
        small.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        small.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 3_500);
        DefaultKafkaProducerFactory<String, String> smallFactory =
                new DefaultKafkaProducerFactory<>(small, new StringSerializer(), new StringSerializer());
        KafkaTemplate<String, String> smallProducer = new KafkaTemplate<>(smallFactory);
        smallProducer.setProducerListener(new ProducerListener<>() { }); // as the production template: the recoverer reports for itself
        String value = "z".repeat(2_000);
        List<String> processed = new CopyOnWriteArrayList<>();

        try (LogCapture logs = new LogCapture()) {
            // the listener's failure message carries the value, so the exception headers are as large as the value
            ConcurrentMessageListenerContainer<String, String> container = startContainer(topic, smallProducer, processed);
            try {
                send(topic, "poison", value);
                send(topic, "sentinel", event(9006));

                await().atMost(Duration.ofSeconds(60)).until(() -> processed.contains("9006"));
                Thread.sleep(2000);

                ConsumerRecord<String, String> deadLetter = findRecord(topic + ".DLT", "poison", Duration.ofSeconds(30)).orElseThrow();
                assertThat(deadLetter.value()).isEqualTo(value);
                assertThat(deadLetter.headers().lastHeader("kafka_dlt-exception-fqcn")).as("sent bare").isNull();
                assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
                assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-offset")).isNotNull();
                assertThat(logs.problems()).as(logs.describe()).hasSize(1);
                assertThat(logs.problems().get(0).getFormattedMessage()).contains("Dead-lettered " + topic);
                assertThat(logs.stackTraceLines()).isZero();
            } finally {
                container.stop();
                smallFactory.destroy();
            }
        }
    }

    private static String newTopic() throws Exception {
        String topic = "error-handling-it-dlt." + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        return topic;
    }

    /**
     * A container from the production {@link KafkaProducerConfig} container factory (same consumer settings, same
     * error handler, same recoverer class), with its own group and a dead-letter publisher that misbehaves. The
     * listener plays the converter: a value that is not JSON fails as a conversion error does.
     */
    private static ConcurrentMessageListenerContainer<String, String> startContainer(
            String topic, KafkaOperations<String, String> deadLetters, List<String> processed) {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", KAFKA.getBootstrapServers());
        DeadLetterRecoverer recoverer = new DeadLetterRecoverer(deadLetters,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1), null);
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(KafkaErrorHandling.errorHandlerFor(recoverer));
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(topic);
        container.getContainerProperties().setGroupId("group-" + UUID.randomUUID());
        container.getContainerProperties().setMessageListener((MessageListener<String, String>) record -> {
            if (!record.value().startsWith("{")) {
                throw new MessageConversionException("not json: " + record.value());
            }
            processed.add(record.value().replaceAll(".*\"orderId\":(\\d+).*", "$1"));
        });
        container.start();
        return container;
    }
}

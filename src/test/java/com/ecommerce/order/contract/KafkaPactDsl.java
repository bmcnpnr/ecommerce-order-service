package com.ecommerce.order.contract;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.core.model.matchingrules.MinTypeMatcher;

import java.time.LocalDateTime;

/**
 * Shared vocabulary for the Kafka message pacts.
 *
 * <p>Every producer in the platform publishes with spring-kafka's {@code JsonSerializer},
 * whose default Jackson 2 mapper has {@code WRITE_DATES_AS_TIMESTAMPS} on. A
 * {@code LocalDateTime} therefore travels as an array of integers
 * {@code [year, month, day, hour, minute, second, nanos]} — seconds and nanos are
 * omitted when zero, so 5 to 7 elements — not as an ISO-8601 string. Every consumer
 * decodes it with the same mapper (via {@code JsonMessageConverter} /
 * {@code JsonDeserializer}), which accepts both forms; the pacts pin the form that is
 * actually on the wire so a producer switching serializers is caught, not discovered
 * in production.
 */
final class KafkaPactDsl {

    private KafkaPactDsl() {
    }

    /** Example value used in every message pact; shape matters, the instant does not. */
    static final LocalDateTime EXAMPLE_TIME = LocalDateTime.of(2026, 8, 23, 14, 30, 15, 123456789);

    /**
     * Adds {@code name} as a LocalDateTime in its Jackson-timestamp (integer array) form.
     * Combine with {@link #timestampArrays(DslPart, String...)} on the finished body so
     * the array may carry 5, 6 or 7 elements.
     */
    static LambdaDslObject localDateTime(LambdaDslObject object, String name, LocalDateTime value) {
        return object.array(name, array -> array
                .integerType((long) value.getYear())
                .integerType((long) value.getMonthValue())
                .integerType((long) value.getDayOfMonth())
                .integerType((long) value.getHour())
                .integerType((long) value.getMinute())
                .integerType((long) value.getSecond())
                .integerType((long) value.getNano()));
    }

    /** Relaxes the length of each named LocalDateTime array to "at least 5 integers". */
    static DslPart timestampArrays(DslPart body, String... names) {
        for (String name : names) {
            body.getMatchers().addRule("$." + name, new MinTypeMatcher(5));
        }
        return body;
    }
}

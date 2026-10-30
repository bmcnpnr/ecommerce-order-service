package com.ecommerce.order.logging;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogThrottleTest {

    private final AtomicLong clock = new AtomicLong();
    private final LogThrottle throttle = new LogThrottle(10, TimeUnit.SECONDS, clock::get);

    private void advanceSeconds(long seconds) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    void firstOccurrenceIsLoggedWithNothingSuppressed() {
        assertThat(throttle.acquire("db-down")).isZero();
    }

    @Test
    void repeatsInsideTheWindowAreSuppressedAndCounted() {
        throttle.acquire("db-down");
        for (int i = 0; i < 5; i++) {
            advanceSeconds(1);
            assertThat(throttle.acquire("db-down")).isEqualTo(-1);
        }

        advanceSeconds(10);

        // the next window logs once more and reports what it swallowed
        assertThat(throttle.acquire("db-down")).isEqualTo(5);
        assertThat(throttle.acquire("db-down")).isEqualTo(-1);
    }

    @Test
    void windowStartsAtTheLoggedOccurrence_notAtTheLastSuppressedOne() {
        throttle.acquire("k");
        advanceSeconds(9);
        assertThat(throttle.acquire("k")).isEqualTo(-1);
        advanceSeconds(1);

        assertThat(throttle.acquire("k")).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        assertThat(throttle.acquire("a")).isZero();
        assertThat(throttle.acquire("b")).isZero();
        assertThat(throttle.acquire("a")).isEqualTo(-1);
        assertThat(throttle.acquire("b")).isEqualTo(-1);
    }

    @Test
    void trackedKeysAreBounded_andOverflowSharesOneBucket() {
        for (int i = 0; i < LogThrottle.MAX_KEYS; i++) {
            assertThat(throttle.acquire("key-" + i)).isZero();
        }

        // the 257th and 258th distinct keys share the overflow bucket: only the first is logged
        assertThat(throttle.acquire("one-too-many")).isZero();
        assertThat(throttle.acquire("and-another")).isEqualTo(-1);
        // and a key that was tracked before the limit still has its own window
        assertThat(throttle.acquire("key-0")).isEqualTo(-1);
    }

    @Test
    void suppressedSuffix_isEmptyUnlessSomethingWasSuppressed() {
        assertThat(LogThrottle.suppressedSuffix(0)).isEmpty();
        assertThat(LogThrottle.suppressedSuffix(-1)).isEmpty();
        assertThat(LogThrottle.suppressedSuffix(7)).isEqualTo(" (7 similar suppressed)");
    }

    @Test
    void keyOf_picksTheFirstFrameInOurOwnCode() {
        Throwable t = new IllegalStateException("boom");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.springframework.jdbc.Foo", "bar", "Foo.java", 10),
                new StackTraceElement("com.ecommerce.order.service.OrderService", "confirm", "OrderService.java", 42),
                new StackTraceElement("com.ecommerce.order.controller.OrderController", "confirm", "OrderController.java", 7)
        });

        assertThat(LogThrottle.keyOf(t))
                .isEqualTo("java.lang.IllegalStateException@com.ecommerce.order.service.OrderService:42");
    }

    @Test
    void keyOf_fallsBackToTheTopFrame_orJustTheClass() {
        Throwable foreign = new IllegalStateException("boom");
        foreign.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.postgresql.Driver", "connect", "Driver.java", 99)});
        Throwable bare = new IllegalStateException("boom");
        bare.setStackTrace(new StackTraceElement[0]);

        assertThat(LogThrottle.keyOf(foreign)).isEqualTo("java.lang.IllegalStateException@org.postgresql.Driver:99");
        assertThat(LogThrottle.keyOf(bare)).isEqualTo("java.lang.IllegalStateException");
    }
}

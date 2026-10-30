package com.ecommerce.order.logging;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Lets tests in other packages drive a {@link LogThrottle} with a fake clock (its clock constructor is package-private). */
public final class ThrottleFixtures {

    private ThrottleFixtures() {
    }

    public static LogThrottle withClock(long window, TimeUnit unit, LongSupplier nanoClock) {
        return new LogThrottle(window, unit, nanoClock);
    }
}

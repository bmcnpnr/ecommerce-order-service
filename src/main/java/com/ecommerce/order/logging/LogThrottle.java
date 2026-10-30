package com.ecommerce.order.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounds how often one kind of log line is written. A failure that repeats at request, poll or
 * scheduler rate (a dead database under load, a bug on a hot path, a flood of bad tokens) logs its
 * first occurrence per window and counts the rest, so log volume grows with the number of distinct
 * problems instead of with traffic. See ecommerce-platform/docs/resilience-fix-spec.md (L7).
 */
public final class LogThrottle {

    /** Distinct keys tracked; past this every new key shares one bucket, so the map stays bounded. */
    static final int MAX_KEYS = 256;
    static final String OVERFLOW_KEY = "<other>";

    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LogThrottle(long window, TimeUnit unit) {
        this(window, unit, System::nanoTime);
    }

    LogThrottle(long window, TimeUnit unit, LongSupplier nanoClock) {
        this.windowNanos = unit.toNanos(window);
        this.nanoClock = nanoClock;
    }

    /**
     * @return {@code -1} when this occurrence must not be logged; otherwise the number of
     *         occurrences suppressed since the last logged one (0 the first time)
     */
    public long acquire(String key) {
        Window window = windows.get(key);
        if (window == null) {
            String k = windows.size() >= MAX_KEYS ? OVERFLOW_KEY : key;
            window = windows.computeIfAbsent(k, ignored -> new Window());
        }
        return window.acquire(nanoClock.getAsLong(), windowNanos);
    }

    /** {@code " (N similar suppressed)"} for a positive count, otherwise {@code ""}. */
    public static String suppressedSuffix(long suppressed) {
        return suppressed > 0 ? " (" + suppressed + " similar suppressed)" : "";
    }

    /** Exception class plus the first stack frame in our own code: one key per distinct failure site. */
    public static String keyOf(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        StackTraceElement site = frames.length > 0 ? frames[0] : null;
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith("com.ecommerce.")) {
                site = frame;
                break;
            }
        }
        return site == null ? t.getClass().getName()
                : t.getClass().getName() + "@" + site.getClassName() + ":" + site.getLineNumber();
    }

    private static final class Window {
        private boolean open;
        private long openedAt;
        private long suppressed;

        synchronized long acquire(long now, long windowNanos) {
            if (open && now - openedAt < windowNanos) {
                suppressed++;
                return -1;
            }
            open = true;
            openedAt = now;
            long count = suppressed;
            suppressed = 0;
            return count;
        }
    }
}

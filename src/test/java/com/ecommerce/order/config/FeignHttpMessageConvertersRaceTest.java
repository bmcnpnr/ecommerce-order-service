package com.ecommerce.order.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;

/**
 * Hammers a cold converters instance from many threads released together, the
 * way the first burst of Feign calls after a pod start hits it. Spring Cloud
 * OpenFeign 5.0.1's FeignHttpMessageConverters publishes an empty list before
 * filling it; the thread-safe subclass registered by FeignConfig must never let
 * a concurrent caller observe that.
 */
class FeignHttpMessageConvertersRaceTest {

    private static <T> ObjectProvider<T> none() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new IllegalStateException("none"); }
            @Override public Stream<T> stream() { return Stream.empty(); }
            @Override public Stream<T> orderedStream() { return Stream.empty(); }
        };
    }

    private static int emptyObservations(FeignHttpMessageConverters converters) throws Exception {
        int threads = 48;
        AtomicInteger empty = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    if (converters.getConverters().isEmpty()) empty.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        return empty.get();
    }

    @Test
    void threadSafeConvertersNeverExposeAnEmptyListToAConcurrentFirstCaller() throws Exception {
        int empties = 0;
        for (int round = 0; round < 200; round++) {
            empties += emptyObservations(new FeignConfig.ThreadSafeFeignHttpMessageConverters(none(), none()));
        }
        assertEquals(0, empties, "a concurrent caller saw an empty converter list");

        FeignHttpMessageConverters warm = new FeignConfig.ThreadSafeFeignHttpMessageConverters(none(), none());
        assertFalse(warm.getConverters().isEmpty());
    }
}

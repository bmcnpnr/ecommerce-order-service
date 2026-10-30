package com.ecommerce.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit breakers log their transitions, not their calls (L5): one WARN when a breaker opens, one INFO
 * when it closes again. The per-call accounting is in the {@code resilience4j_circuitbreaker_*} metrics
 * and the CircuitBreakerOpen alert, so a fallback or a rejected call during an outage writes no line.
 */
@Component
public class CircuitBreakerTransitionLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);

    private final Set<String> watched = ConcurrentHashMap.newKeySet();

    public CircuitBreakerTransitionLogger(CircuitBreakerRegistry registry) {
        // Subscribe first, then walk the existing ones: a breaker created in between is seen by both and
        // de-duplicated by name.
        registry.getEventPublisher().onEntryAdded(event -> watch(event.getAddedEntry()));
        registry.getAllCircuitBreakers().forEach(this::watch);
    }

    private void watch(CircuitBreaker breaker) {
        if (!watched.add(breaker.getName())) {
            return;
        }
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State to = event.getStateTransition().getToState();
            switch (to) {
                case OPEN, FORCED_OPEN -> log.warn("Circuit breaker '{}' {} -> {}: calls now fail fast (failure rate {}%)",
                        breaker.getName(), event.getStateTransition().getFromState(), to,
                        Math.round(breaker.getMetrics().getFailureRate()));
                case CLOSED -> log.info("Circuit breaker '{}' {} -> CLOSED: calls flow again",
                        breaker.getName(), event.getStateTransition().getFromState());
                default -> log.debug("Circuit breaker '{}' {} -> {}", breaker.getName(),
                        event.getStateTransition().getFromState(), to);
            }
        });
    }
}

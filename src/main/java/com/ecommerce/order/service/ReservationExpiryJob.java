package com.ecommerce.order.service;

import com.ecommerce.order.exception.Unavailability;
import com.ecommerce.order.logging.LogThrottle;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cancels abandoned carts. A PENDING order reserves stock the moment an item is added and holds it until
 * somebody confirms or cancels, so a cart nobody comes back to takes that stock out of the shop for good.
 * This job cancels every PENDING order that has not changed for longer than
 * {@code orders.reservation.pending-ttl} (default 24 hours), by the path a customer's cancel takes
 * ({@link OrderService#expireAbandonedCart}): status and {@code order.cancelled} commit together, then the
 * stock goes back.
 *
 * <p><b>CONFIRMED orders are never expired.</b> Their payment may still be on its way (payment-service keeps
 * {@code payment.completed} in its outbox while Kafka is down), so age alone could cancel a paid order.
 *
 * <p>Safe with several replicas: each replica selects the same oldest candidates, but the cancellation of one
 * order is decided inside a transaction guarded by the order's status and version, so exactly one replica
 * cancels it and returns its stock; the others skip it silently.
 *
 * <p>One INFO line per run, and only when the run expired something; {@code orders.reservations.expired}
 * counts every expired cart.
 */
@Component
@ConditionalOnProperty(name = "orders.reservation.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final OrderRepository orders;
    private final OrderService service;
    private final Clock clock;
    private final Duration ttl;
    private final int batchSize;
    private final Counter expired;

    /** The job is periodic, so a dependency that is down repeats every run: once a minute at most. */
    private final LogThrottle problemLog = new LogThrottle(60, TimeUnit.SECONDS);

    @Autowired
    public ReservationExpiryJob(OrderRepository orders, OrderService service, MeterRegistry meterRegistry,
                                @Value("${orders.reservation.pending-ttl:PT24H}") Duration ttl,
                                @Value("${orders.reservation.expiry.batch-size:100}") int batchSize) {
        this(orders, service, meterRegistry, Clock.systemDefaultZone(), ttl, batchSize);
    }

    ReservationExpiryJob(OrderRepository orders, OrderService service, MeterRegistry meterRegistry,
                         Clock clock, Duration ttl, int batchSize) {
        this.orders = orders;
        this.service = service;
        this.clock = clock;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            // A zero TTL would cancel every cart on the next run; fall back rather than refuse to start.
            log.warn("orders.reservation.pending-ttl must be positive (was {}); using {}", ttl, DEFAULT_TTL);
            ttl = DEFAULT_TTL;
        }
        this.ttl = ttl;
        this.batchSize = Math.max(1, batchSize);
        this.expired = Counter.builder("orders.reservations.expired")
                .description("Abandoned PENDING carts cancelled by the expiry job").register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${orders.reservation.expiry.initial-delay-ms:60000}",
            fixedDelayString = "${orders.reservation.expiry.interval-ms:600000}")
    public void expireAbandonedCarts() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(ttl);
        List<Long> candidates;
        try {
            candidates = orders.findIdsIdleSince(OrderStatus.PENDING, cutoff, PageRequest.of(0, batchSize));
        } catch (RuntimeException e) {
            reportFailure(e);
            return;
        }
        int expiredThisRun = 0;
        for (Long orderId : candidates) {
            OrderService.ExpiryOutcome outcome;
            try {
                outcome = service.expireAbandonedCart(orderId, cutoff);
            } catch (RuntimeException e) {
                reportFailure(e); // the database, most likely: leave the rest for the next run
                break;
            }
            if (outcome == OrderService.ExpiryOutcome.PEER_UNAVAILABLE) {
                // Cancelling now would lose the stock the cart holds. The cart and the rest wait for the next run.
                long suppressed = problemLog.acquire("expiry.peer");
                if (suppressed >= 0) {
                    log.warn("Cart expiry paused: product-service is not answering, so reserved stock cannot be returned{}",
                            LogThrottle.suppressedSuffix(suppressed));
                }
                break;
            }
            if (outcome == OrderService.ExpiryOutcome.EXPIRED) {
                expiredThisRun++;
                expired.increment();
            }
        }
        if (expiredThisRun > 0) {
            log.info("Expired {} abandoned cart(s) idle since before {}", expiredThisRun, cutoff);
        }
    }

    private void reportFailure(RuntimeException failure) {
        long suppressed = problemLog.acquire("expiry.failure");
        if (suppressed < 0) {
            return;
        }
        Throwable dependency = Unavailability.dependencyFailure(failure);
        if (dependency != null) {
            // An outage, not a bug: no stack trace; the next run tries again.
            log.warn("Cart expiry skipped this run, a dependency is unavailable: {}{}",
                    Unavailability.describe(dependency), LogThrottle.suppressedSuffix(suppressed));
        } else {
            log.error("Cart expiry failed{}", LogThrottle.suppressedSuffix(suppressed), failure);
        }
    }
}

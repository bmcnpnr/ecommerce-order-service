package com.ecommerce.order.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService.ExpiryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Which carts the expiry job asks about (the age), how it keeps count, and how little it says.
 * The decision about one cart is {@link OrderServiceCancellationTest}; the query is {@code OrderRepositoryIT}.
 */
@ExtendWith(MockitoExtension.class)
class ReservationExpiryJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0, 0);

    @Mock private OrderRepository orders;
    @Mock private OrderService service;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ReservationExpiryJob job;

    private ListAppender<ILoggingEvent> logs;
    private Logger jobLogger;

    @BeforeEach
    void setUp() {
        job = jobWith(Duration.ofHours(24), 100);
        jobLogger = (Logger) LoggerFactory.getLogger(ReservationExpiryJob.class);
        logs = new ListAppender<>();
        logs.start();
        jobLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logs);
    }

    private ReservationExpiryJob jobWith(Duration ttl, int batchSize) {
        return new ReservationExpiryJob(orders, service, meters, CLOCK, ttl, batchSize);
    }

    private double expiredCount() {
        return meters.get("orders.reservations.expired").counter().count();
    }

    private List<ILoggingEvent> lines(Level atLeast) {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(atLeast)).toList();
    }

    // ---- which carts: the age ----

    @Test
    void asksForPendingOrdersIdleSinceBeforeNowMinusTheTtl_oldestFirst_inABoundedBatch() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of());

        job.expireAbandonedCarts();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(orders).findIdsIdleSince(eq(OrderStatus.PENDING), eq(NOW.minusHours(24)), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
        verifyNoInteractions(service);
    }

    @Test
    void theTtlAndTheBatchSizeAreConfigurable_andABatchOfZeroStillMakesProgress() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of());

        jobWith(Duration.parse("PT36H"), 25).expireAbandonedCarts();
        jobWith(Duration.ofMinutes(30), 0).expireAbandonedCarts();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(orders).findIdsIdleSince(eq(OrderStatus.PENDING), eq(NOW.minusHours(36)), page.capture());
        verify(orders).findIdsIdleSince(eq(OrderStatus.PENDING), eq(NOW.minusMinutes(30)), page.capture());
        assertThat(page.getAllValues()).extracting(Pageable::getPageSize).containsExactly(25, 1);
    }

    @Test
    void aTtlThatIsNotPositive_fallsBackToTwentyFourHours_insteadOfCancellingEveryCart() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of());

        jobWith(Duration.ZERO, 100).expireAbandonedCarts();
        jobWith(Duration.ofHours(-1), 100).expireAbandonedCarts();
        jobWith(null, 100).expireAbandonedCarts();

        verify(orders, times(3)).findIdsIdleSince(eq(OrderStatus.PENDING), eq(NOW.minusHours(24)), any());
    }

    @Test
    void everyCandidateIsDecidedAgainstTheSameCutoff() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(7L, 8L));
        given(service.expireAbandonedCart(anyLong(), any())).willReturn(ExpiryOutcome.SKIPPED);

        job.expireAbandonedCarts();

        verify(service).expireAbandonedCart(7L, NOW.minusHours(24));
        verify(service).expireAbandonedCart(8L, NOW.minusHours(24));
    }

    // ---- counting and talking ----

    @Test
    void countsWhatItExpired_andSaysSoInOneInfoLine() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(1L, 2L, 3L));
        given(service.expireAbandonedCart(1L, NOW.minusHours(24))).willReturn(ExpiryOutcome.EXPIRED);
        given(service.expireAbandonedCart(2L, NOW.minusHours(24))).willReturn(ExpiryOutcome.SKIPPED); // another replica got it
        given(service.expireAbandonedCart(3L, NOW.minusHours(24))).willReturn(ExpiryOutcome.EXPIRED);

        job.expireAbandonedCarts();

        assertThat(expiredCount()).isEqualTo(2.0);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.get(0).getFormattedMessage()).startsWith("Expired 2 abandoned cart(s)");
    }

    @Test
    void aRunThatExpiresNothing_saysNothing() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(1L, 2L));
        given(service.expireAbandonedCart(anyLong(), any())).willReturn(ExpiryOutcome.SKIPPED);

        for (int run = 0; run < 50; run++) {
            job.expireAbandonedCarts();
        }

        assertThat(logs.list).isEmpty();
        assertThat(expiredCount()).isZero();
    }

    // ---- when things are down ----

    @Test
    void whenProductServiceDoesNotAnswer_theRunStops_andOneWarnSaysWhy() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(1L, 2L, 3L));
        given(service.expireAbandonedCart(1L, NOW.minusHours(24))).willReturn(ExpiryOutcome.EXPIRED);
        given(service.expireAbandonedCart(2L, NOW.minusHours(24))).willReturn(ExpiryOutcome.PEER_UNAVAILABLE);

        job.expireAbandonedCarts();

        verify(service, never()).expireAbandonedCart(eq(3L), any());
        assertThat(expiredCount()).isEqualTo(1.0);
        assertThat(lines(Level.WARN)).hasSize(1);
        assertThat(lines(Level.WARN).get(0).getFormattedMessage()).contains("product-service is not answering");
        assertThat(lines(Level.INFO)).as("the WARN and the INFO about the one that was expired").hasSize(2);
    }

    @Test
    void aDeadPeerForHundredsOfRuns_costsOneLine() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(1L));
        given(service.expireAbandonedCart(anyLong(), any())).willReturn(ExpiryOutcome.PEER_UNAVAILABLE);

        for (int run = 0; run < 300; run++) {
            job.expireAbandonedCarts();
        }

        assertThat(logs.list).hasSize(1);
        assertThat(expiredCount()).isZero();
    }

    @Test
    void whenTheDatabaseIsDown_theRunIsSkipped_withOneWarnAndNoStackTrace_andNeverThrows() {
        given(orders.findIdsIdleSince(any(), any(), any())).willThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        for (int run = 0; run < 100; run++) {
            job.expireAbandonedCarts();
        }

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(0).getThrowableProxy()).isNull();
        assertThat(logs.list.get(0).getFormattedMessage()).contains("CannotCreateTransactionException");
        verifyNoInteractions(service);
    }

    @Test
    void whenTheDatabaseDiesMidRun_theRestWaitsForTheNextRun() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(1L, 2L, 3L));
        given(service.expireAbandonedCart(1L, NOW.minusHours(24))).willReturn(ExpiryOutcome.EXPIRED);
        given(service.expireAbandonedCart(2L, NOW.minusHours(24))).willThrow(new DataAccessResourceFailureException("connection reset"));

        job.expireAbandonedCarts();

        verify(service, never()).expireAbandonedCart(eq(3L), any());
        assertThat(expiredCount()).isEqualTo(1.0);
        assertThat(lines(Level.WARN)).hasSize(1);
        assertThat(lines(Level.WARN).get(0).getThrowableProxy()).isNull();
    }

    @Test
    void aBugIsLoggedWithItsStackTrace_once_andDoesNotKillTheScheduler() {
        given(orders.findIdsIdleSince(any(), any(), any())).willReturn(List.of(1L));
        given(service.expireAbandonedCart(anyLong(), any())).willThrow(new IllegalStateException("bug"));

        for (int run = 0; run < 100; run++) {
            job.expireAbandonedCarts();
        }

        assertThat(lines(Level.ERROR)).hasSize(1);
        assertThat(lines(Level.ERROR).get(0).getThrowableProxy()).isNotNull();
    }

    // ---- the defaults the platform relies on ----

    @Test
    void itRunsEveryTenMinutes_afterAMinute_andIsOnUnlessSwitchedOff() throws Exception {
        Scheduled schedule = ReservationExpiryJob.class.getMethod("expireAbandonedCarts").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = ReservationExpiryJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(schedule.fixedDelayString()).isEqualTo("${orders.reservation.expiry.interval-ms:600000}");
        assertThat(schedule.initialDelayString()).isEqualTo("${orders.reservation.expiry.initial-delay-ms:60000}");
        assertThat(condition.name()).containsExactly("orders.reservation.expiry.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

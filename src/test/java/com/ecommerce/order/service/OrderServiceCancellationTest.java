package com.ecommerce.order.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.dto.StockUpdateRequest;
import com.ecommerce.order.event.OrderCancelledEvent;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService.ExpiryOutcome;
import com.ecommerce.order.service.OrderService.PaymentFailureOutcome;
import com.ecommerce.order.service.OrderService.PaymentFailureResult;
import com.ecommerce.order.service.OrderServiceResilienceTest.TrackingTransactionManager;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The two cancellations that nobody asked for over HTTP: a failed payment (I-4) and an abandoned cart.
 * Both cancel by the path a customer's cancel takes (commit first, give the stock back afterwards, outside the
 * transaction) and both must be idempotent, because the first is driven by an at-least-once event and the second
 * runs on every replica.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceCancellationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 30, 12, 0, 0);

    @Mock private OrderRepository orderRepository;
    @Mock private ProductServiceClient productServiceClient;
    @Mock private OrderEventProducer orderEventProducer;

    private final TrackingTransactionManager transactions = new TrackingTransactionManager();
    private OrderService service;

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, productServiceClient, orderEventProducer, transactions);
        serviceLogger = (Logger) LoggerFactory.getLogger(OrderService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private static Order order(OrderStatus status, LocalDateTime lastChange, int... quantitiesOfProducts1To) {
        Order order = Order.builder().orderId(5L).customerId("user1").status(status).orderDate(lastChange)
                .updatedAt(lastChange).totalAmount(BigDecimal.ZERO).orderItems(new ArrayList<>()).build();
        for (int i = 0; i < quantitiesOfProducts1To.length; i++) {
            order.addItem(OrderItem.builder().productId(i + 1L).productName("p" + (i + 1))
                    .productPrice(new BigDecimal("2.00")).quantity(quantitiesOfProducts1To[i]).build());
        }
        return order;
    }

    private static RetryableException peerDown() {
        Request request = Request.create(Request.HttpMethod.PATCH, "http://product-service/internal/v1/products/1/stock",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Connection refused", Request.HttpMethod.PATCH, new java.net.ConnectException("refused"), (Long) null, request);
    }

    private static FeignException httpError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/1", Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("ProductServiceClient#getProductById(Long)", feign.Response.builder().status(status)
                .reason("r").request(request).headers(Map.of()).body("x", StandardCharsets.UTF_8).build());
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    // ---- payment.failed -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED"})
    void failedPayment_cancelsTheOrder_thenReturnsItsStock_outsideTheTransaction_andPublishesNothing(OrderStatus status) {
        Order order = order(status, CUTOFF.minusDays(1), 2, 4);
        given(orderRepository.findById(5L)).willReturn(Optional.of(order));
        List<String> trail = new ArrayList<>();
        given(productServiceClient.updateStock(any(), any())).willAnswer(inv -> {
            trail.add("restore@open=" + transactions.open.get() + "/committed=" + transactions.events.contains("commit"));
            return null;
        });

        PaymentFailureResult result = service.cancelAfterFailedPayment(5L);

        assertThat(result.outcome()).isEqualTo(PaymentFailureOutcome.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
        ArgumentCaptor<StockUpdateRequest> deltas = ArgumentCaptor.forClass(StockUpdateRequest.class);
        verify(productServiceClient, times(2)).updateStock(any(), deltas.capture());
        assertThat(deltas.getAllValues()).extracting(StockUpdateRequest::getDelta).containsExactly(2, 4);
        assertThat(trail).containsExactly("restore@open=0/committed=true", "restore@open=0/committed=true");
        verifyNoInteractions(orderEventProducer); // order.cancelled stays the answer to a cancel somebody asked for
    }

    /** The replay of an at-least-once event: the order is already CANCELLED, so no stock goes back a second time. */
    @Test
    void aReplayedFailedPayment_changesNothing_andReturnsNoStockAgain() {
        Order order = order(OrderStatus.CONFIRMED, CUTOFF.minusDays(1), 3);
        given(orderRepository.findById(5L)).willReturn(Optional.of(order));

        PaymentFailureResult first = service.cancelAfterFailedPayment(5L);
        PaymentFailureResult replay = service.cancelAfterFailedPayment(5L);
        PaymentFailureResult replayAgain = service.cancelAfterFailedPayment(5L);

        assertThat(first.outcome()).isEqualTo(PaymentFailureOutcome.CANCELLED);
        assertThat(replay.outcome()).isEqualTo(PaymentFailureOutcome.ALREADY_CANCELLED);
        assertThat(replayAgain.outcome()).isEqualTo(PaymentFailureOutcome.ALREADY_CANCELLED);
        verify(productServiceClient, times(1)).updateStock(eq(1L), any());
        verify(orderRepository, times(1)).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "DELIVERED"})
    void aFailedPaymentForAnOrderPastConfirmation_changesNothing(OrderStatus status) {
        Order order = order(status, CUTOFF.minusDays(1), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(order));

        PaymentFailureResult result = service.cancelAfterFailedPayment(5L);

        assertThat(result).isEqualTo(new PaymentFailureResult(PaymentFailureOutcome.NOT_APPLICABLE, status));
        assertThat(order.getStatus()).isEqualTo(status);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(productServiceClient, orderEventProducer);
    }

    @Test
    void aFailedPaymentForAnUnknownOrder_isReported_andChangesNothing() {
        given(orderRepository.findById(99L)).willReturn(Optional.empty());

        assertThat(service.cancelAfterFailedPayment(99L).outcome()).isEqualTo(PaymentFailureOutcome.UNKNOWN_ORDER);

        verifyNoInteractions(productServiceClient, orderEventProducer);
    }

    /** Raced by a customer's cancel: the later commit fails on the version. It is thrown, so the listener retries and finds the end state. */
    @Test
    void aFailedPaymentThatLosesTheRace_failsOnTheVersion_andReturnsNoStock() {
        Order order = order(OrderStatus.CONFIRMED, CUTOFF.minusDays(1), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willThrow(new ObjectOptimisticLockingFailureException(Order.class, 5L));

        assertThatThrownBy(() -> service.cancelAfterFailedPayment(5L)).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verifyNoInteractions(productServiceClient, orderEventProducer);
        assertThat(transactions.events).containsExactly("begin", "rollback");
    }

    @Test
    void aFailedPayment_withProductServiceDown_stillCancels_triesOneLine_andNamesWhatWasNotReturned() {
        Order order = order(OrderStatus.CONFIRMED, CUTOFF.minusDays(1), 2, 4, 6);
        given(orderRepository.findById(5L)).willReturn(Optional.of(order));
        given(productServiceClient.updateStock(any(), any()))
                .willThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("product-service")));

        PaymentFailureResult result = service.cancelAfterFailedPayment(5L);

        assertThat(result.outcome()).isEqualTo(PaymentFailureOutcome.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(productServiceClient, times(1)).updateStock(any(), any());
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage())
                .contains("3 line(s)").contains("productId=1 x2").contains("productId=2 x4").contains("productId=3 x6");
    }

    @Test
    void aFailedPayment_forAnOrderWithNoLines_callsNobody() {
        given(orderRepository.findById(5L)).willReturn(Optional.of(order(OrderStatus.PENDING, CUTOFF.minusDays(1))));

        assertThat(service.cancelAfterFailedPayment(5L).outcome()).isEqualTo(PaymentFailureOutcome.CANCELLED);

        verifyNoInteractions(productServiceClient, orderEventProducer);
    }

    // ---- abandoned carts ------------------------------------------------------------------------

    @Test
    void aCartIdleLongerThanTheTtl_isCancelledByTheCustomersPath_eventFirst_thenTheStock() {
        Order cart = order(OrderStatus.PENDING, CUTOFF.minusNanos(1), 2, 4);
        given(orderRepository.findById(5L)).willReturn(Optional.of(cart));
        List<Integer> openWhenEventWritten = new ArrayList<>();
        List<String> restores = new ArrayList<>();
        doAnswer(inv -> openWhenEventWritten.add(transactions.open.get())).when(orderEventProducer).publishOrderCancelled(any());
        given(productServiceClient.updateStock(any(), any())).willAnswer(inv -> {
            restores.add("open=" + transactions.open.get() + "/committed=" + transactions.events.contains("commit"));
            return null;
        });

        ExpiryOutcome outcome = service.expireAbandonedCart(5L, CUTOFF);

        assertThat(outcome).isEqualTo(ExpiryOutcome.EXPIRED);
        assertThat(cart.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        ArgumentCaptor<OrderCancelledEvent> event = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(orderEventProducer).publishOrderCancelled(event.capture());
        assertThat(event.getValue().getOrderId()).isEqualTo(5L);
        assertThat(event.getValue().getCustomerId()).isEqualTo("user1");
        assertThat(openWhenEventWritten).as("the event is written inside the cancelling transaction").containsExactly(1);
        assertThat(restores).containsExactly("open=0/committed=true", "open=0/committed=true");
        // one probe of product-service before cancelling, then one restore per line
        verify(productServiceClient, times(1)).getProductById(1L);
        verify(productServiceClient, times(2)).updateStock(any(), any());
    }

    // The age is "strictly older than the TTL": a cart exactly at the cutoff is not abandoned yet.

    @Test
    void aCartExactlyAtTheCutoff_isNotExpired() {
        given(orderRepository.findById(5L)).willReturn(Optional.of(order(OrderStatus.PENDING, CUTOFF, 1)));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderEventProducer);
        verify(productServiceClient, never()).updateStock(any(), any());
    }

    @Test
    void aCartOneNanosecondOlderThanTheCutoff_isExpired() {
        given(orderRepository.findById(5L)).willReturn(Optional.of(order(OrderStatus.PENDING, CUTOFF.minusNanos(1), 1)));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.EXPIRED);
    }

    @Test
    void aCartTouchedAfterTheCutoff_isNotExpired() {
        given(orderRepository.findById(5L)).willReturn(Optional.of(order(OrderStatus.PENDING, CUTOFF.plusSeconds(1), 1)));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);
    }

    @Test
    void aRowWithoutUpdatedAt_isAgedByItsCreationDate() {
        Order legacy = order(OrderStatus.PENDING, CUTOFF.minusDays(3), 1);
        legacy.setUpdatedAt(null);
        Order legacyFresh = order(OrderStatus.PENDING, CUTOFF.plusHours(1), 1);
        legacyFresh.setUpdatedAt(null);
        given(orderRepository.findById(5L)).willReturn(Optional.of(legacy), Optional.of(legacy));
        given(orderRepository.findById(6L)).willReturn(Optional.of(legacyFresh));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.EXPIRED);
        assertThat(service.expireAbandonedCart(6L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);
    }

    /** CONFIRMED is never expired: its payment may be on its way (payment-service holds payment.completed in its outbox during a Kafka outage). */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void anOrderThatIsNotPending_isNeverExpired_howeverOldItIs(OrderStatus status) {
        Order old = order(status, CUTOFF.minusDays(30), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(old));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);

        assertThat(old.getStatus()).isEqualTo(status);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderEventProducer, productServiceClient);
    }

    @Test
    void aCandidateThatNoLongerExists_isSkipped() {
        given(orderRepository.findById(5L)).willReturn(Optional.empty());

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);
    }

    /** The customer added an item between the job's read and the cancelling transaction. */
    @Test
    void aCartTouchedBetweenTheReadAndTheTransaction_isLeftAlone() {
        Order idle = order(OrderStatus.PENDING, CUTOFF.minusDays(1), 1);
        Order touched = order(OrderStatus.PENDING, CUTOFF.plusMinutes(1), 1, 3);
        given(orderRepository.findById(5L)).willReturn(Optional.of(idle), Optional.of(touched));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);

        assertThat(touched.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderEventProducer);
        verify(productServiceClient, never()).updateStock(any(), any());
    }

    /** Two replicas selected the same cart; the other one committed first. Silent, and no second restore. */
    @Test
    void aCartAnotherReplicaCancelledFirst_failsOnTheVersion_isSkippedSilently_andReturnsNoStock() {
        Order cart = order(OrderStatus.PENDING, CUTOFF.minusDays(1), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(cart));
        given(orderRepository.save(any())).willThrow(new ObjectOptimisticLockingFailureException(Order.class, 5L));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);

        verify(productServiceClient, never()).updateStock(any(), any());
        assertThat(logs.list).as("a lost race is not worth a line").isEmpty();
    }

    @Test
    void theSameCartExpiredTwice_returnsItsStockOnce() {
        Order cart = order(OrderStatus.PENDING, CUTOFF.minusDays(1), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(cart));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.EXPIRED);
        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.SKIPPED);

        verify(productServiceClient, times(1)).updateStock(eq(1L), any());
        verify(orderEventProducer, times(1)).publishOrderCancelled(any());
    }

    @Test
    void anEmptyCart_expiresWithoutTouchingProductService() {
        given(orderRepository.findById(5L)).willReturn(Optional.of(order(OrderStatus.PENDING, CUTOFF.minusDays(1))));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.EXPIRED);

        verifyNoInteractions(productServiceClient);
        verify(orderEventProducer).publishOrderCancelled(any());
    }

    /** Cancelling a cart whose stock cannot be returned would lose that stock for good: wait for the next run. */
    @Test
    void whileProductServiceDoesNotAnswer_aCartWithLinesIsLeftForTheNextRun() {
        Order cart = order(OrderStatus.PENDING, CUTOFF.minusDays(1), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(cart));
        given(productServiceClient.getProductById(1L)).willThrow(peerDown());

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.PEER_UNAVAILABLE);

        assertThat(cart.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderEventProducer);
        verify(productServiceClient, never()).updateStock(any(), any());
    }

    @Test
    void anOpenBreakerOrA5xx_countsAsNotAnswering_butA404IsAnAnswer() {
        Order cart = order(OrderStatus.PENDING, CUTOFF.minusDays(1), 2);
        given(orderRepository.findById(5L)).willReturn(Optional.of(cart));
        given(productServiceClient.getProductById(1L))
                .willThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("product-service")))
                .willThrow(httpError(500))
                .willThrow(httpError(404));

        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.PEER_UNAVAILABLE);
        assertThat(service.expireAbandonedCart(5L, CUTOFF)).isEqualTo(ExpiryOutcome.PEER_UNAVAILABLE);
        assertThat(service.expireAbandonedCart(5L, CUTOFF)).as("the product is unknown, product-service is up").isEqualTo(ExpiryOutcome.EXPIRED);
    }
}

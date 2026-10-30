package com.ecommerce.order.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.dto.ProductDTO;
import com.ecommerce.order.dto.StockUpdateRequest;
import com.ecommerce.order.exception.InvalidOrderStateException;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the order service does around its calls to product-service (H2, R-2, partial failure):
 * no database transaction is open while the peer is called, a reservation that could not be saved is
 * given back, a cancellation is committed before its stock is returned, and a dead peer is not hammered.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceResilienceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductServiceClient productServiceClient;
    @Mock private OrderEventProducer orderEventProducer;

    /** Counts open transactions and records commit/rollback, so tests can ask "was a transaction open when X happened". */
    static final class TrackingTransactionManager implements PlatformTransactionManager {
        final AtomicInteger open = new AtomicInteger();
        final List<String> events = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            open.incrementAndGet();
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            open.decrementAndGet();
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            open.decrementAndGet();
            events.add("rollback");
        }
    }

    private final TrackingTransactionManager transactions = new TrackingTransactionManager();
    private OrderService service;
    private Order order;

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, productServiceClient, orderEventProducer, transactions);
        order = Order.builder().orderId(1L).customerId("user1").status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO).orderItems(new ArrayList<>()).build();
        serviceLogger = (Logger) LoggerFactory.getLogger(OrderService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private static ProductDTO product(String price, Integer stock) {
        return ProductDTO.builder().id(7L).sku("SKU-7").name("Shoe").price(new BigDecimal(price)).stockQuantity(stock).build();
    }

    private static OrderItem line(long productId, int quantity) {
        return OrderItem.builder().productId(productId).productName("p" + productId)
                .productPrice(new BigDecimal("2.00")).quantity(quantity).build();
    }

    private static RetryableException peerDown() {
        Request request = Request.create(Request.HttpMethod.PATCH, "http://product-service/internal/v1/products/1/stock",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Connection refused", Request.HttpMethod.PATCH, new java.net.ConnectException("refused"), (Long) null, request);
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    // ---- no transaction across a call to product-service ----

    @Test
    void addItem_callsProductServiceOutsideAnyTransaction_andSavesInAShortOne() {
        List<Integer> openWhenCalled = new ArrayList<>();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.getProductById(7L)).willAnswer(inv -> {
            openWhenCalled.add(transactions.open.get());
            return product("10.00", 50);
        });
        given(productServiceClient.updateStock(eq(7L), any())).willAnswer(inv -> {
            openWhenCalled.add(transactions.open.get());
            return product("10.00", 48);
        });
        given(orderRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        OrderDTO result = service.addItemToOrder(1L, 7L, 2);

        assertThat(openWhenCalled).as("transactions open during the two remote calls").containsExactly(0, 0);
        assertThat(transactions.events).containsExactly("begin", "commit");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void cancel_commitsFirst_thenReturnsStockOutsideTheTransaction() {
        order.setStatus(OrderStatus.CONFIRMED);
        order.addItem(line(1L, 2));
        order.addItem(line(2L, 4));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        List<String> trail = new ArrayList<>();
        given(productServiceClient.updateStock(any(), any())).willAnswer(inv -> {
            trail.add("restore@open=" + transactions.open.get() + "/committed=" + transactions.events.contains("commit"));
            return null;
        });

        service.cancelOrder(1L);

        assertThat(trail).containsExactly("restore@open=0/committed=true", "restore@open=0/committed=true");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancel_writesTheEventInsideTheTransaction_soItExistsExactlyWhenTheCancellationDoes() {
        order.setStatus(OrderStatus.PAID);
        order.addItem(line(1L, 1));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        List<Integer> open = new ArrayList<>();
        doAnswer(inv -> open.add(transactions.open.get())).when(orderEventProducer).publishOrderCancelled(any());

        service.cancelOrder(1L);

        assertThat(open).containsExactly(1);
    }

    @Test
    void confirm_writesTheEventInsideTheTransaction() {
        order.addItem(line(1L, 1));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        List<Integer> open = new ArrayList<>();
        doAnswer(inv -> open.add(transactions.open.get())).when(orderEventProducer).publishOrderPlaced(any());

        service.confirmOrder(1L);

        assertThat(open).containsExactly(1);
        assertThat(transactions.events).containsExactly("begin", "commit");
    }

    @Test
    void aFailedCancellation_rollsBackAndReturnsNoStock() {
        order.setStatus(OrderStatus.CONFIRMED);
        order.addItem(line(1L, 2));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(orderRepository.save(any())).willThrow(new ObjectOptimisticLockingFailureException(Order.class, 1L));

        assertThatThrownBy(() -> service.cancelOrder(1L)).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // the loser of a concurrent cancel must not return the stock the winner returns
        verify(productServiceClient, never()).updateStock(any(), any());
        assertThat(transactions.events).containsExactly("begin", "rollback");
    }

    // ---- a reservation that could not be saved is given back ----

    @Test
    void addItem_whenTheLineCannotBeSaved_theReservationIsReleased_andTheOriginalFailureSurvives() {
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.getProductById(7L)).willReturn(product("10.00", 50));
        ObjectOptimisticLockingFailureException conflict = new ObjectOptimisticLockingFailureException(Order.class, 1L);
        given(orderRepository.save(any())).willThrow(conflict);

        assertThatThrownBy(() -> service.addItemToOrder(1L, 7L, 3)).isSameAs(conflict);

        ArgumentCaptor<StockUpdateRequest> deltas = ArgumentCaptor.forClass(StockUpdateRequest.class);
        verify(productServiceClient, times(2)).updateStock(eq(7L), deltas.capture());
        assertThat(deltas.getAllValues()).extracting(StockUpdateRequest::getDelta).containsExactly(-3, 3);
    }

    @Test
    void addItem_whenTheOrderWasCancelledMeanwhile_theReservationIsReleased() {
        Order cancelledMeanwhile = Order.builder().orderId(1L).customerId("user1").status(OrderStatus.CANCELLED)
                .totalAmount(BigDecimal.ZERO).orderItems(new ArrayList<>()).build();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order), Optional.of(cancelledMeanwhile));
        given(productServiceClient.getProductById(7L)).willReturn(product("10.00", 50));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 7L, 2))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Cannot add items to order in status: CANCELLED");

        ArgumentCaptor<StockUpdateRequest> deltas = ArgumentCaptor.forClass(StockUpdateRequest.class);
        verify(productServiceClient, times(2)).updateStock(eq(7L), deltas.capture());
        assertThat(deltas.getAllValues()).extracting(StockUpdateRequest::getDelta).containsExactly(-2, 2);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void addItem_ifTheReleaseItselfFails_theCallerStillSeesTheOriginalFailure_andOneWarnIsLogged() {
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.getProductById(7L)).willReturn(product("10.00", 50));
        IllegalStateException saveFailure = new IllegalStateException("disk full");
        given(orderRepository.save(any())).willThrow(saveFailure);
        given(productServiceClient.updateStock(eq(7L), any()))
                .willReturn(product("10.00", 47))   // the reservation
                .willThrow(peerDown());              // the release

        assertThatThrownBy(() -> service.addItemToOrder(1L, 7L, 3)).isSameAs(saveFailure);

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("3 unit(s) of productId=7 could not be released");
    }

    @Test
    void addItem_whenTheReservationItselfFails_nothingIsReleased() {
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.getProductById(7L)).willReturn(product("10.00", 50));
        given(productServiceClient.updateStock(eq(7L), any())).willThrow(peerDown());

        assertThatThrownBy(() -> service.addItemToOrder(1L, 7L, 3)).isInstanceOf(RetryableException.class);

        // after a read timeout the reservation may have been applied; guessing "+3" would be a second guess
        verify(productServiceClient, times(1)).updateStock(eq(7L), any());
        verify(orderRepository, never()).save(any());
    }

    // ---- bounds ----

    @Test
    void addItem_toAnOrderWithTheMaximumNumberOfLines_isRefusedBeforeAnyRemoteCall() {
        for (int i = 0; i < OrderService.MAX_LINES_PER_ORDER; i++) {
            order.addItem(line(i, 1));
        }
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 7L, 1))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("An order can have at most 200 lines");

        org.mockito.Mockito.verifyNoInteractions(productServiceClient);
    }

    @Test
    void addItem_whoseLineWouldOverflowTheTotalColumn_isRefusedBeforeTheStockIsReserved() {
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.getProductById(7L)).willReturn(product("1000000000000.00", 5000)); // 10^12 x 1000 = 10^15 > NUMERIC(19,4)

        assertThatThrownBy(() -> service.addItemToOrder(1L, 7L, 1000))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("The order total is too large");

        verify(productServiceClient, never()).updateStock(any(), any());
    }

    // ---- cancelling while product-service is down ----

    @Test
    void cancel_withTheBreakerOpen_triesOneLineOnly_logsOneWarnNamingWhatWasNotReturned_andStillCancels() {
        order.setStatus(OrderStatus.CONFIRMED);
        order.addItem(line(1L, 2));
        order.addItem(line(2L, 4));
        order.addItem(line(3L, 6));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.updateStock(any(), any()))
                .willThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("product-service")));

        service.cancelOrder(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(productServiceClient, times(1)).updateStock(any(), any());   // not three calls against a dead peer
        verify(orderEventProducer).publishOrderCancelled(any());
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage())
                .contains("3 line(s)").contains("productId=1 x2").contains("productId=2 x4").contains("productId=3 x6");
    }

    @Test
    void cancel_aLineThatFailsForItsOwnReason_doesNotStopTheOtherLines() {
        order.setStatus(OrderStatus.CONFIRMED);
        order.addItem(line(1L, 2));
        order.addItem(line(2L, 4));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(productServiceClient.updateStock(eq(1L), any())).willThrow(new IllegalArgumentException("unknown product"));

        service.cancelOrder(1L);

        verify(productServiceClient).updateStock(eq(2L), any());
    }

    @Test
    void adminCancelThroughTheStatusEndpoint_returnsStockToo() {
        order.setStatus(OrderStatus.PAID);
        order.addItem(line(1L, 2));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderDTO result = service.updateOrderStatus(1L, OrderStatus.CANCELLED);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        verify(productServiceClient).updateStock(eq(1L), any());
        verify(orderEventProducer).publishOrderCancelled(any());
    }
}

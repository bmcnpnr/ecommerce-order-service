package com.ecommerce.order.security;

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
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Business-logic tampering at the service layer: where the price comes from, which states allow
 * which operations, and that a refused operation has no side effect (no stock movement, no event).
 */
@Tag("security")
@ExtendWith(MockitoExtension.class)
class OrderServiceSecurityTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductServiceClient productServiceClient;
    @Mock private OrderEventProducer orderEventProducer;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, productServiceClient, orderEventProducer);
        org.mockito.Mockito.lenient().when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Order order(long id, OrderStatus status) {
        Order order = Order.builder().orderId(id).customerId("alice").status(status).build();
        order.addItem(OrderItem.builder().productId(1L).productName("Shoe")
                .productPrice(new BigDecimal("20.00")).quantity(2).build());
        return order;
    }

    private static ProductDTO product(String price, Integer stock) {
        ProductDTO product = new ProductDTO();
        product.setId(1L);
        product.setSku("SKU-1");
        product.setName("Shoe");
        product.setPrice(price == null ? null : new BigDecimal(price));
        product.setStockQuantity(stock);
        return product;
    }

    private void givenOrder(Order order) {
        given(orderRepository.findById(order.getOrderId())).willReturn(Optional.of(order));
    }

    // ---- price integrity ------------------------------------------------------------------------

    @Test
    void addItem_takesThePriceFromProductService_andComputesTheTotalItself() {
        Order order = Order.builder().orderId(1L).customerId("alice").status(OrderStatus.PENDING).build();
        givenOrder(order);
        given(productServiceClient.getProductById(1L)).willReturn(product("29.99", 10));

        OrderDTO dto = service.addItemToOrder(1L, 1L, 3);

        assertThat(dto.getTotalAmount()).isEqualByComparingTo("89.97");
        assertThat(dto.getOrderItems()).singleElement().satisfies(item ->
                assertThat(item.getProductPrice()).isEqualByComparingTo("29.99"));
    }

    @Disabled("BUG S-63: a negative price coming back from product-service is added as-is, so the total goes down and the order can be 'paid' for less than nothing")
    @Test
    void addItem_negativeUpstreamPrice_isRejected() {
        Order order = Order.builder().orderId(1L).customerId("alice").status(OrderStatus.PENDING).build();
        givenOrder(order);
        given(productServiceClient.getProductById(1L)).willReturn(product("-50.00", 10));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 1L, 1)).isInstanceOf(RuntimeException.class);

        assertThat(order.getTotalAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(order.getOrderItems()).isEmpty();
    }

    @Disabled("BUG S-63: a product with a null price is added to the order at no cost (Order.addItem silently skips the total)")
    @Test
    void addItem_nullUpstreamPrice_isRejected() {
        Order order = Order.builder().orderId(1L).customerId("alice").status(OrderStatus.PENDING).build();
        givenOrder(order);
        given(productServiceClient.getProductById(1L)).willReturn(product(null, 10));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 1L, 1)).isInstanceOf(RuntimeException.class);

        assertThat(order.getOrderItems()).isEmpty();
    }

    @Test
    void addItem_moreThanInStock_isRejected_andReservesNothing() {
        Order order = Order.builder().orderId(1L).customerId("alice").status(OrderStatus.PENDING).build();
        givenOrder(order);
        given(productServiceClient.getProductById(1L)).willReturn(product("1.00", 5));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 1L, Integer.MAX_VALUE))
                .isInstanceOf(InvalidOrderStateException.class);

        verify(productServiceClient, never()).updateStock(anyLong(), any(StockUpdateRequest.class));
        assertThat(order.getOrderItems()).isEmpty();
    }

    // ---- which states accept new items -----------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCELLED", "PAID"})
    void addItem_toClosedOrder_isRejected_withoutTouchingStock(OrderStatus status) {
        givenOrder(order(1L, status));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 1L, 1)).isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(productServiceClient);
    }

    @Disabled("BUG S-64: items can still be added after the order was confirmed (and shipped/delivered), so the total changes after order.placed carried the amount to be paid")
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "SHIPPED", "DELIVERED"})
    void addItem_toOrderPastPending_isRejected(OrderStatus status) {
        Order order = order(1L, status);
        givenOrder(order);
        given(productServiceClient.getProductById(1L)).willReturn(product("5.00", 10));

        assertThatThrownBy(() -> service.addItemToOrder(1L, 1L, 1)).isInstanceOf(InvalidOrderStateException.class);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("40.00");
    }

    // ---- status transitions ------------------------------------------------------------------------

    static Stream<org.junit.jupiter.params.provider.Arguments> illegalTransitions() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.PENDING, OrderStatus.DELIVERED),
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.PENDING, OrderStatus.PAID),
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.PENDING, OrderStatus.SHIPPED),
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAID),
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.CANCELLED, OrderStatus.CONFIRMED),
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.DELIVERED, OrderStatus.PENDING),
                org.junit.jupiter.params.provider.Arguments.of(OrderStatus.PAID, OrderStatus.PENDING));
    }

    @Disabled("BUG I-8: updateOrderStatus performs no state-machine check, so any status can be forced from any status (PENDING -> DELIVERED, CANCELLED -> PAID, ...)")
    @ParameterizedTest
    @MethodSource("illegalTransitions")
    void updateStatus_illegalTransition_isRejected_andNothingIsSaved(OrderStatus from, OrderStatus to) {
        Order order = order(1L, from);
        givenOrder(order);

        assertThatThrownBy(() -> service.updateOrderStatus(1L, to)).isInstanceOf(InvalidOrderStateException.class);

        assertThat(order.getStatus()).isEqualTo(from);
        verify(orderRepository, never()).save(any());
    }

    // ---- cancel / confirm replay ----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCELLED", "SHIPPED", "DELIVERED"})
    void cancel_inTerminalOrShippedState_isRejected_withNoStockRestoreAndNoEvent(OrderStatus status) {
        givenOrder(order(1L, status));

        assertThatThrownBy(() -> service.cancelOrder(1L)).isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(productServiceClient, orderEventProducer);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"})
    void confirm_replay_isRejected_withNoSecondOrderPlacedEvent(OrderStatus status) {
        givenOrder(order(1L, status));

        assertThatThrownBy(() -> service.confirmOrder(1L)).isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(orderEventProducer);
    }

    @Test
    void confirm_emptyOrder_isRejected_withNoEvent() {
        givenOrder(Order.builder().orderId(1L).customerId("alice").status(OrderStatus.PENDING).build());

        assertThatThrownBy(() -> service.confirmOrder(1L)).isInstanceOf(InvalidOrderStateException.class);

        verifyNoInteractions(orderEventProducer);
        verify(orderRepository, never()).save(any());
        verifyNoMoreInteractions(productServiceClient);
    }
}

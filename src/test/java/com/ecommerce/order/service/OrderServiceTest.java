package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.event.OrderCancelledEvent;
import com.ecommerce.order.event.OrderPlacedEvent;
import com.ecommerce.order.exception.*;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.*;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductServiceClient productServiceClient;
    @Mock private OrderEventProducer orderEventProducer;

    @InjectMocks private OrderService orderService;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        testOrder = Order.builder()
                .orderId(1L).customerId("user1").status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO).orderItems(new ArrayList<>())
                .build();
    }

    @Test
    void createOrder_success() {
        when(orderRepository.save(any())).thenAnswer(inv -> { Order o = inv.getArgument(0); o.setOrderId(1L); return o; });
        OrderDTO result = orderService.createOrder("user1");
        assertThat(result.getCustomerId()).isEqualTo("user1");
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void addItemToOrder_success() {
        ProductDTO product = ProductDTO.builder().id(1L).name("Test").price(new BigDecimal("10.00")).stockQuantity(50).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.getProductById(1L)).thenReturn(product);
        when(productServiceClient.updateStock(eq(1L), any())).thenReturn(product);
        when(orderRepository.save(any())).thenReturn(testOrder);

        OrderDTO result = orderService.addItemToOrder(1L, 1L, 2);
        verify(productServiceClient).updateStock(eq(1L), argThat(req -> req.getDelta() == -2));
    }

    @Test
    void addItemToOrder_orderNotFound_throwsException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.addItemToOrder(99L, 1L, 1))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void addItemToOrder_cancelledOrder_throwsException() {
        testOrder.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        assertThatThrownBy(() -> orderService.addItemToOrder(1L, 1L, 1))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void cancelOrder_alreadyShipped_throwsException() {
        testOrder.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        assertThatThrownBy(() -> orderService.cancelOrder(1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Cannot cancel order in status: SHIPPED");
    }

    @Test
    void getOrderById_notFound_throwsException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.getOrderById(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // --- helpers -------------------------------------------------------------

    private static OrderItem item(long productId, String name, String price, int quantity) {
        return OrderItem.builder().productId(productId).productName(name)
                .productPrice(new BigDecimal(price)).quantity(quantity).build();
    }

    private void stubSaveReturnsArgument() {
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- addItemToOrder ------------------------------------------------------

    @Test
    void addItemToOrder_addsItemAndAccumulatesTotal() {
        testOrder.addItem(item(7L, "Existing", "5.00", 1));
        ProductDTO product = ProductDTO.builder().id(1L).name("Test").price(new BigDecimal("10.00")).stockQuantity(50).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.getProductById(1L)).thenReturn(product);
        stubSaveReturnsArgument();

        OrderDTO result = orderService.addItemToOrder(1L, 1L, 3);

        assertThat(result.getTotalAmount()).isEqualByComparingTo("35.00");
        assertThat(result.getOrderItems()).hasSize(2);
        OrderItemDTO added = result.getOrderItems().get(1);
        assertThat(added.getProductId()).isEqualTo(1L);
        assertThat(added.getProductName()).isEqualTo("Test");
        assertThat(added.getProductPrice()).isEqualByComparingTo("10.00");
        assertThat(added.getQuantity()).isEqualTo(3);
        assertThat(added.getOrderId()).isEqualTo(1L);
    }

    @Test
    void addItemToOrder_paidOrder_throwsAndLeavesStockAlone() {
        testOrder.setStatus(OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() -> orderService.addItemToOrder(1L, 1L, 1))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Cannot add items to order in status: PAID");
        verifyNoInteractions(productServiceClient);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void addItemToOrder_insufficientStock_throwsWithoutReservingStock() {
        ProductDTO product = ProductDTO.builder().id(1L).sku("SKU-1").name("Test").price(BigDecimal.TEN).stockQuantity(2).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.getProductById(1L)).thenReturn(product);

        assertThatThrownBy(() -> orderService.addItemToOrder(1L, 1L, 3))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Insufficient stock for product SKU-1. Available: 2");
        verify(productServiceClient, never()).updateStock(any(), any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void addItemToOrder_exactlyAvailableStock_isAccepted() {
        ProductDTO product = ProductDTO.builder().id(1L).name("Test").price(BigDecimal.ONE).stockQuantity(3).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.getProductById(1L)).thenReturn(product);
        stubSaveReturnsArgument();

        orderService.addItemToOrder(1L, 1L, 3);

        verify(productServiceClient).updateStock(eq(1L), argThat(req -> req.getDelta() == -3));
    }

    @Test
    void addItemToOrder_unknownStockQuantity_skipsStockCheck() {
        ProductDTO product = ProductDTO.builder().id(1L).name("Test").price(BigDecimal.ONE).stockQuantity(null).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.getProductById(1L)).thenReturn(product);
        stubSaveReturnsArgument();

        OrderDTO result = orderService.addItemToOrder(1L, 1L, 100);

        assertThat(result.getOrderItems()).hasSize(1);
        verify(productServiceClient).updateStock(eq(1L), argThat(req -> req.getDelta() == -100));
    }

    @Test
    void addItemToOrder_productLookupFails_propagatesAndChangesNothing() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.getProductById(1L))
                .thenThrow(new RuntimeException("Product service unavailable. Please try again later."));

        assertThatThrownBy(() -> orderService.addItemToOrder(1L, 1L, 1))
                .hasMessageContaining("unavailable");
        verify(productServiceClient, never()).updateStock(any(), any());
        verify(orderRepository, never()).save(any());
        assertThat(testOrder.getOrderItems()).isEmpty();
    }

    // --- confirmOrder (place) ------------------------------------------------

    @Test
    void confirmOrder_pendingWithItems_confirmsAndPublishesOrderPlaced() {
        LocalDateTime orderDate = LocalDateTime.of(2026, 10, 29, 9, 30);
        testOrder.setOrderDate(orderDate);
        testOrder.addItem(item(1L, "Shoe", "10.00", 2));
        testOrder.addItem(item(2L, "Sock", "2.50", 4));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        stubSaveReturnsArgument();

        OrderDTO result = orderService.confirmOrder(1L);

        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        verify(orderRepository).save(argThat(o -> o.getStatus() == OrderStatus.CONFIRMED));
        ArgumentCaptor<OrderPlacedEvent> captor = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(orderEventProducer).publishOrderPlaced(captor.capture());
        OrderPlacedEvent event = captor.getValue();
        assertThat(event.getOrderId()).isEqualTo(1L);
        assertThat(event.getCustomerId()).isEqualTo("user1");
        assertThat(event.getTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(event.getOrderDate()).isEqualTo(orderDate);
        assertThat(event.getItems())
                .extracting(OrderPlacedEvent.OrderItemEvent::getProductId,
                        OrderPlacedEvent.OrderItemEvent::getProductName,
                        OrderPlacedEvent.OrderItemEvent::getQuantity)
                .containsExactly(tuple(1L, "Shoe", 2), tuple(2L, "Sock", 4));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void confirmOrder_notPending_throwsAndPublishesNothing(OrderStatus status) {
        testOrder.setStatus(status);
        testOrder.addItem(item(1L, "Shoe", "10.00", 1));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() -> orderService.confirmOrder(1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Can only confirm PENDING orders. Current status: " + status);
        verifyNoInteractions(orderEventProducer);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void confirmOrder_withoutItems_throws() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() -> orderService.confirmOrder(1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Cannot confirm an order with no items");
        verifyNoInteractions(orderEventProducer);
        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void confirmOrder_notFound_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.confirmOrder(99L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("Order not found: 99");
    }

    // --- cancelOrder ---------------------------------------------------------

    @Test
    void cancelOrder_pending_cancelsRestoresStockAndPublishes() {
        testOrder.addItem(item(1L, "Shoe", "10.00", 2));
        testOrder.addItem(item(2L, "Sock", "2.50", 4));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        orderService.cancelOrder(1L);

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(testOrder);
        verify(productServiceClient).updateStock(eq(1L), argThat(req -> req.getDelta() == 2));
        verify(productServiceClient).updateStock(eq(2L), argThat(req -> req.getDelta() == 4));
        ArgumentCaptor<OrderCancelledEvent> captor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(orderEventProducer).publishOrderCancelled(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(1L);
        assertThat(captor.getValue().getCustomerId()).isEqualTo("user1");
        assertThat(captor.getValue().getCancelledAt()).isNotNull();
    }

    @Test
    void cancelOrder_stockRestoreFailure_isLoggedAndDoesNotAbortCancellation() {
        testOrder.addItem(item(1L, "Shoe", "10.00", 2));
        testOrder.addItem(item(2L, "Sock", "2.50", 4));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(productServiceClient.updateStock(eq(1L), any())).thenThrow(new RuntimeException("boom"));

        orderService.cancelOrder(1L);

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(productServiceClient).updateStock(eq(2L), argThat(req -> req.getDelta() == 4));
        verify(orderEventProducer).publishOrderCancelled(any());
    }

    @Test
    void cancelOrder_delivered_throwsAndRestoresNothing() {
        testOrder.setStatus(OrderStatus.DELIVERED);
        testOrder.addItem(item(1L, "Shoe", "10.00", 2));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() -> orderService.cancelOrder(1L))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Cannot cancel order in status: DELIVERED");
        verifyNoInteractions(productServiceClient, orderEventProducer);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelOrder_notFound_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.cancelOrder(99L))
                .isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(productServiceClient, orderEventProducer);
    }

    @Test
    void cancelOrder_alreadyCancelled_isRejectedWithoutRestoringStockAgain() {
        testOrder.setStatus(OrderStatus.CANCELLED);
        testOrder.addItem(item(1L, "Shoe", "10.00", 2));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() -> orderService.cancelOrder(1L))
                .isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(productServiceClient, orderEventProducer);
    }

    // --- queries / status ----------------------------------------------------

    @Test
    void getOrderById_mapsEntityToDto() {
        testOrder.addItem(item(1L, "Shoe", "10.00", 2));
        testOrder.getOrderItems().get(0).setOrderItemId(11L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));

        OrderDTO dto = orderService.getOrderById(1L);

        assertThat(dto.getOrderId()).isEqualTo(1L);
        assertThat(dto.getStatus()).isEqualTo("PENDING");
        assertThat(dto.getTotalAmount()).isEqualByComparingTo("20.00");
        assertThat(dto.getOrderItems()).singleElement().satisfies(i -> {
            assertThat(i.getOrderItemId()).isEqualTo(11L);
            assertThat(i.getOrderId()).isEqualTo(1L);
        });
    }

    @Test
    void getOrdersByCustomer_mapsEveryOrder() {
        Order other = Order.builder().orderId(2L).customerId("user1").status(OrderStatus.PAID).build();
        when(orderRepository.findByCustomerId("user1")).thenReturn(List.of(testOrder, other));

        assertThat(orderService.getOrdersByCustomer("user1"))
                .extracting(OrderDTO::getOrderId, OrderDTO::getStatus)
                .containsExactly(tuple(1L, "PENDING"), tuple(2L, "PAID"));
    }

    @Test
    void getOrdersByCustomer_none_returnsEmptyList() {
        when(orderRepository.findByCustomerId("nobody")).thenReturn(List.of());
        assertThat(orderService.getOrdersByCustomer("nobody")).isEmpty();
    }

    @Test
    void updateOrderStatus_setsStatusAndSaves() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        stubSaveReturnsArgument();

        OrderDTO result = orderService.updateOrderStatus(1L, OrderStatus.SHIPPED);

        assertThat(result.getStatus()).isEqualTo("SHIPPED");
        verify(orderRepository).save(argThat(o -> o.getStatus() == OrderStatus.SHIPPED));
    }

    @Test
    void updateOrderStatus_notFound_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.updateOrderStatus(99L, OrderStatus.SHIPPED))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void getHelloMessage_returnsGreeting() {
        assertThat(orderService.getHelloMessage()).isEqualTo("Hello from Order Service!");
    }
}

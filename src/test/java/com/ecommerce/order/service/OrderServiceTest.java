package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductServiceClient;
import com.ecommerce.order.dto.*;
import com.ecommerce.order.event.OrderPlacedEvent;
import com.ecommerce.order.exception.*;
import com.ecommerce.order.messaging.OrderEventProducer;
import com.ecommerce.order.model.*;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductServiceClient productServiceClient;
    @Mock private ModelMapper modelMapper;
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
}

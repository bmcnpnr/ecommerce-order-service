package com.ecommerce.order.service;

import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.dto.ProductDTO;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static com.ecommerce.order.service.OrderService.HELLO_FROM_ORDER_SERVICE;
import static com.ecommerce.order.util.TestUtil.generateOrderDTO;
import static com.ecommerce.order.util.TestUtil.generateOrderEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    @Spy
    private ModelMapper modelMapper = new ModelMapper();

    @Mock
    private ProductServiceClient productServiceClient;

    @Test
    void testGetHelloMessage() {
        // when
        var result = orderService.getHelloMessage();

        // then
        assertEquals(HELLO_FROM_ORDER_SERVICE, result);
    }

    @Test
    void testCreateOrder() {
        // given
        LocalDateTime now = LocalDateTime.now();
        var orderDTO = OrderDTO.builder()
                .customerId("user_1")
                .orderDate(now)
                .orderItems(new ArrayList<>())
                .build();

        var order = Order.builder()
                .customerId("user_1")
                .orderDate(now)
                .orderItems(new ArrayList<>())
                .build();

        // when
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        var result = orderService.createOrder("user_1");

        // then
        assertEquals(orderDTO, result);
    }

    @Test
    void testAddProductToOrder() {
        // given
        var orderDTO = generateOrderDTO();
        var orderOptional = Optional.of(generateOrderEntity());
        var productDTO = ProductDTO.builder()
                .id("nike_1")
                .name("nike shoes")
                .price(BigDecimal.valueOf(12.23))
                .build();

        // when
        when(orderRepository.findById(anyLong())).thenReturn(orderOptional);
        when(orderRepository.save(any(Order.class))).thenReturn(orderOptional.orElseThrow());
        when(productServiceClient.getProductDetails(anyString())).thenReturn(productDTO);

        // then
        var result = orderService.addProductToOrder(1L, "nike_1");
        assertNotEquals(orderDTO, result);
    }
}

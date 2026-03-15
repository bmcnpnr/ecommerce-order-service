package com.ecommerce.order.util;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.dto.OrderItemDTO;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;

@UtilityClass
public class TestUtil {
    public static OrderDTO generateOrderDTO() {
        return OrderDTO.builder()
                .orderId(1L)
                .customerId("user_1")
                .orderItems(new ArrayList<>())
                .orderDate(LocalDateTime.now())
                .billingAddress("Eindhoven")
                .shippingAddress("Eindhoven")
                .totalAmount(BigDecimal.ONE)
                .status("PENDING")
                .build();
    }

    public static Order generateOrderEntity() {
        return Order.builder()
                .orderId(1L)
                .customerId("user_1")
                .orderItems(new ArrayList<>())
                .orderDate(LocalDateTime.now())
                .billingAddress("Eindhoven")
                .shippingAddress("Eindhoven")
                .totalAmount(BigDecimal.ONE)
                .status(OrderStatus.PENDING)
                .build();
    }

    public static OrderItem generateOrderItemEntity(final Order order) {
        return OrderItem.builder()
                .orderItemId(1L)
                .order(order)
                .productId(1L)
                .productName("nike shoes")
                .productPrice(BigDecimal.valueOf(1.5))
                .quantity(1)
                .build();
    }

    public static OrderItemDTO generateOrderItemDTO(final Long orderId) {
        return OrderItemDTO.builder()
                .orderItemId(1L)
                .orderId(orderId)
                .productId(1L)
                .productName("nike shoes")
                .productPrice(BigDecimal.valueOf(1.5))
                .quantity(1)
                .build();
    }
}

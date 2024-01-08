package com.ecommerce.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public final class OrderDTO {
    private Long orderId;

    private String customerId;

    private List<OrderItemDTO> orderItems;

    private LocalDateTime orderDate;

    private String billingAddress;

    private String shippingAddress;

    private BigDecimal totalAmount;
}

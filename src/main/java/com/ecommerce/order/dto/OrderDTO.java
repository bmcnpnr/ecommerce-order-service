package com.ecommerce.order.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDTO {
    private Long orderId;
    private String customerId;
    private List<OrderItemDTO> orderItems;
    private LocalDateTime orderDate;
    private String billingAddress;
    private String shippingAddress;
    private BigDecimal totalAmount;
    private String status;
}

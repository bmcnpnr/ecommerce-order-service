package com.ecommerce.order.dto;

import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDTO {
    private Long orderItemId;
    private Long orderId;        // Only the ID, no circular OrderDTO reference
    private Long productId;
    private String productName;
    private BigDecimal productPrice;
    private Integer quantity;
}

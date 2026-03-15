package com.ecommerce.order.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderPlacedEvent {
    private Long orderId;
    private String customerId;
    private BigDecimal totalAmount;
    private LocalDateTime orderDate;
    private List<OrderItemEvent> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItemEvent {
        private Long productId;
        private String productName;
        private BigDecimal productPrice;
        private Integer quantity;
    }
}

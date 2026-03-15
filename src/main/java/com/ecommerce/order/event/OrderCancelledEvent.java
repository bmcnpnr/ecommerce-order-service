package com.ecommerce.order.event;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCancelledEvent {
    private Long orderId;
    private String customerId;
    private LocalDateTime cancelledAt;
}

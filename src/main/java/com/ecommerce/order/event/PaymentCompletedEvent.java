package com.ecommerce.order.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentCompletedEvent {
    private Long paymentId;
    private Long orderId;
    private String customerId;
    private BigDecimal amount;
    private String transactionId;
    private LocalDateTime completedAt;
}

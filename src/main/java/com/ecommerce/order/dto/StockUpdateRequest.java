package com.ecommerce.order.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockUpdateRequest {
    private Integer delta;
}

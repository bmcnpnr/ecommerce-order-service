package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. The gateway never routes {@code /internal/**}, so these are
 * reachable only from inside the cluster and need no identity.
 */
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
@Hidden
public class InternalOrderController {

    private final OrderService orderService;

    /** Used by payment-service to validate a payment against the order (status, owner, total). */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDTO> getOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }
}

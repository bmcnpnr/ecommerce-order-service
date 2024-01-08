package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public final class OrderController {

    private final OrderService orderService;

    @Autowired
    public OrderController(final OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/hello")
    public String sayHello() {
        return orderService.getHelloMessage();
    }

    @PostMapping
    public ResponseEntity<OrderDTO> createOrder(final @RequestParam String customerId) {
        // Logic to handle order creation
        return ResponseEntity.ok(orderService.createOrder(customerId));
    }

    //todo add multiple product adding later.
    @PostMapping("/{orderId}/products")
    public ResponseEntity<OrderDTO> addItemToOrder(final @PathVariable Long orderId, final @RequestParam String productId) {
        // Logic to handle adding products to order
        return ResponseEntity.ok(orderService.addProductToOrder(orderId, productId));
    }
}

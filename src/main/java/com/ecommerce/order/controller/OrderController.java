package com.ecommerce.order.controller;

import com.ecommerce.order.dto.AddItemRequest;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order management")
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/hello")
    @Operation(summary = "Health check hello")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok(orderService.getHelloMessage());
    }

    @PostMapping
    @Operation(summary = "Create a new order")
    public ResponseEntity<OrderDTO> createOrder(@RequestParam String customerId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(customerId));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<OrderDTO> getOrderById(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get orders by customer ID")
    public ResponseEntity<List<OrderDTO>> getOrdersByCustomer(@PathVariable String customerId) {
        return ResponseEntity.ok(orderService.getOrdersByCustomer(customerId));
    }

    @PostMapping("/{orderId}/items")
    @Operation(summary = "Add item to order")
    public ResponseEntity<OrderDTO> addItemToOrder(@PathVariable Long orderId,
                                                    @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.ok(orderService.addItemToOrder(orderId, request.getProductId(), request.getQuantity()));
    }

    @PostMapping("/{orderId}/confirm")
    @Operation(summary = "Confirm order and trigger payment flow")
    public ResponseEntity<OrderDTO> confirmOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.confirmOrder(orderId));
    }

    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Update order status (admin/internal)")
    public ResponseEntity<OrderDTO> updateStatus(@PathVariable Long orderId,
                                                  @RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, status));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel an order")
    public ResponseEntity<Void> cancelOrder(@PathVariable Long orderId) {
        orderService.cancelOrder(orderId);
        return ResponseEntity.noContent().build();
    }

    // Legacy endpoint for backwards compatibility
    @PostMapping("/{orderId}/products")
    @Operation(summary = "Add product to order (legacy - use /items instead)")
    public ResponseEntity<OrderDTO> addProductToOrder(@PathVariable Long orderId,
                                                       @RequestParam Long productId,
                                                       @RequestParam(defaultValue = "1") Integer quantity) {
        return ResponseEntity.ok(orderService.addItemToOrder(orderId, productId, quantity));
    }
}

package com.ecommerce.order.controller;

import com.ecommerce.order.dto.AddItemRequest;
import com.ecommerce.order.dto.OrderDTO;
import com.ecommerce.order.exception.BadRequestException;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.security.OrderAccess;
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

    private static final String USER = "X-Username";
    private static final String ROLE = "X-User-Role";
    static final int MAX_QUANTITY = 1000;

    private final OrderService orderService;
    private final OrderAccess access;

    @GetMapping("/hello")
    @Operation(summary = "Health check hello")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok(orderService.getHelloMessage());
    }

    @PostMapping
    @Operation(summary = "Create a new order (owner or admin; customerId defaults to the caller)")
    public ResponseEntity<OrderDTO> createOrder(@RequestParam(required = false) String customerId,
                                                @RequestHeader(value = USER, required = false) String username,
                                                @RequestHeader(value = ROLE, required = false) String role) {
        String owner = access.resolveCustomer(username, role, customerId);
        if (owner == null || owner.isBlank()) {
            throw new BadRequestException("customerId is required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(owner));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID (owner or admin)")
    public ResponseEntity<OrderDTO> getOrderById(@PathVariable Long orderId,
                                                 @RequestHeader(value = USER, required = false) String username,
                                                 @RequestHeader(value = ROLE, required = false) String role) {
        access.requireOwnerOrAdmin(username, role, orderId);
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get orders by customer ID (that customer or admin)")
    public ResponseEntity<List<OrderDTO>> getOrdersByCustomer(@PathVariable String customerId,
                                                              @RequestHeader(value = USER, required = false) String username,
                                                              @RequestHeader(value = ROLE, required = false) String role) {
        access.requireCustomerOrAdmin(username, role, customerId);
        return ResponseEntity.ok(orderService.getOrdersByCustomer(customerId));
    }

    @PostMapping("/{orderId}/items")
    @Operation(summary = "Add item to order (owner or admin)")
    public ResponseEntity<OrderDTO> addItemToOrder(@PathVariable Long orderId,
                                                    @Valid @RequestBody AddItemRequest request,
                                                    @RequestHeader(value = USER, required = false) String username,
                                                    @RequestHeader(value = ROLE, required = false) String role) {
        access.requireOwnerOrAdmin(username, role, orderId);
        return ResponseEntity.ok(orderService.addItemToOrder(orderId, request.getProductId(), request.getQuantity()));
    }

    @PostMapping("/{orderId}/confirm")
    @Operation(summary = "Confirm order and trigger payment flow (owner or admin)")
    public ResponseEntity<OrderDTO> confirmOrder(@PathVariable Long orderId,
                                                 @RequestHeader(value = USER, required = false) String username,
                                                 @RequestHeader(value = ROLE, required = false) String role) {
        access.requireOwnerOrAdmin(username, role, orderId);
        return ResponseEntity.ok(orderService.confirmOrder(orderId));
    }

    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Update order status (admin only, legal transitions only)")
    public ResponseEntity<OrderDTO> updateStatus(@PathVariable Long orderId,
                                                  @RequestParam OrderStatus status,
                                                  @RequestHeader(value = USER, required = false) String username,
                                                  @RequestHeader(value = ROLE, required = false) String role) {
        access.requireAdmin(username, role);
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, status));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel an order (owner or admin)")
    public ResponseEntity<Void> cancelOrder(@PathVariable Long orderId,
                                            @RequestHeader(value = USER, required = false) String username,
                                            @RequestHeader(value = ROLE, required = false) String role) {
        access.requireOwnerOrAdmin(username, role, orderId);
        orderService.cancelOrder(orderId);
        return ResponseEntity.noContent().build();
    }

    // Legacy endpoint for backwards compatibility
    @PostMapping("/{orderId}/products")
    @Operation(summary = "Add product to order (legacy - use /items instead)")
    public ResponseEntity<OrderDTO> addProductToOrder(@PathVariable Long orderId,
                                                       @RequestParam Long productId,
                                                       @RequestParam(defaultValue = "1") Integer quantity,
                                                       @RequestHeader(value = USER, required = false) String username,
                                                       @RequestHeader(value = ROLE, required = false) String role) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BadRequestException("quantity must be between 1 and " + MAX_QUANTITY);
        }
        access.requireOwnerOrAdmin(username, role, orderId);
        return ResponseEntity.ok(orderService.addItemToOrder(orderId, productId, quantity));
    }
}

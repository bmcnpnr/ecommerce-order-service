package com.ecommerce.order.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<OrderItem> orderItems = new ArrayList<>();

    @Column(name = "order_date", nullable = false)
    @Builder.Default
    private LocalDateTime orderDate = LocalDateTime.now();

    @Column(name = "billing_address", columnDefinition = "TEXT")
    private String billingAddress;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    /**
     * Optimistic lock (V4 migration). Adding an item calls product-service between reading and writing the
     * order, outside any database transaction, so two writers can start from the same state; the loser's
     * UPDATE finds a newer version and fails instead of overwriting the other's total or status.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private Long version;

    /**
     * When the order last changed (V5 migration), set by {@link #touch()} on insert and on every update that
     * writes the row. {@code ReservationExpiryJob} cancels a PENDING cart that has been idle for longer than
     * the reservation TTL, so this has to move whenever the customer touches the cart.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** When the order last changed; the creation date for a row that predates the column. */
    public LocalDateTime lastChange() {
        return updatedAt != null ? updatedAt : orderDate;
    }

    public void addItem(OrderItem orderItem) {
        if (orderItems == null) {
            orderItems = new ArrayList<>();
        }
        orderItem.setOrder(this);
        orderItems.add(orderItem);
        if (orderItem.getProductPrice() != null && orderItem.getQuantity() != null) {
            totalAmount = totalAmount.add(
                orderItem.getProductPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity()))
            );
        }
    }
}

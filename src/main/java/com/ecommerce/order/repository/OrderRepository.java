package com.ecommerce.order.repository;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Optional;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    /** Always paged: a customer's order history is unbounded. */
    List<Order> findByCustomerId(String customerId, Pageable pageable);

    @Query("select o.customerId from Order o where o.orderId = :orderId")
    Optional<String> findCustomerIdByOrderId(@Param("orderId") Long orderId);

    /**
     * Ids of the orders in {@code status} whose last change is strictly before {@code cutoff}, idle longest first.
     * Ids only: the caller re-reads each order and re-checks it inside its own transaction.
     */
    @Query("select o.orderId from Order o where o.status = :status and o.updatedAt < :cutoff "
            + "order by o.updatedAt asc, o.orderId asc")
    List<Long> findIdsIdleSince(@Param("status") OrderStatus status, @Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}

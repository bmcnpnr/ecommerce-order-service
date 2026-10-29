package com.ecommerce.order.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTransitionTest {

    @Test
    void transitionTable() {
        assertThat(OrderStatus.PENDING.allowedNext()).containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
        assertThat(OrderStatus.CONFIRMED.allowedNext()).containsExactlyInAnyOrder(OrderStatus.PAID, OrderStatus.CANCELLED);
        assertThat(OrderStatus.PAID.allowedNext()).containsExactlyInAnyOrder(OrderStatus.SHIPPED, OrderStatus.CANCELLED);
        assertThat(OrderStatus.SHIPPED.allowedNext()).containsExactly(OrderStatus.DELIVERED);
        assertThat(OrderStatus.DELIVERED.allowedNext()).isEmpty();
        assertThat(OrderStatus.CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void noStatusMovesToItself_orBackToPending() {
        for (OrderStatus from : OrderStatus.values()) {
            assertThat(from.canTransitionTo(from)).as("%s -> itself", from).isFalse();
            assertThat(from.canTransitionTo(OrderStatus.PENDING)).as("%s -> PENDING", from).isFalse();
        }
    }
}

package com.ecommerce.order.integration;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adding an item calls product-service between reading the order and writing it, outside any transaction, so
 * two writers can start from the same state. The version column (V4) makes the later one fail instead of
 * silently overwriting the earlier one's total or status.
 */
class OrderConcurrencyIT extends ApplicationTestBase {

    @Autowired private OrderRepository orders;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private long seed(OrderStatus status) {
        Order order = Order.builder().customerId("cust-concurrency").status(status).orderDate(LocalDateTime.now())
                .totalAmount(BigDecimal.ZERO).build();
        order.addItem(OrderItem.builder().productId(1L).productName("Shoe").productPrice(new BigDecimal("10.00")).quantity(1).build());
        return orders.saveAndFlush(order).getOrderId();
    }

    @Test
    void aWriterThatStartedFromAnOlderVersion_failsInsteadOfOverwritingTheNewerOne() {
        long id = seed(OrderStatus.PENDING);
        TransactionTemplate slowWriter = new TransactionTemplate(transactionManager);
        TransactionTemplate fastWriter = new TransactionTemplate(transactionManager);
        fastWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> slowWriter.executeWithoutResult(status -> {
            Order stale = orders.findById(id).orElseThrow();            // version 0
            fastWriter.executeWithoutResult(other -> {                  // somebody else confirms it meanwhile
                Order current = orders.findById(id).orElseThrow();
                current.setStatus(OrderStatus.CONFIRMED);
                orders.saveAndFlush(current);
            });
            stale.setStatus(OrderStatus.CANCELLED);                     // ... and this one still believes it is PENDING
            orders.saveAndFlush(stale);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("select status from orders where order_id = ?", String.class, id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select version from orders where order_id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    void anAddedLine_bumpsTheVersionThroughTheTotal_soTwoConcurrentAddsCannotBothWin() {
        long id = seed(OrderStatus.PENDING);
        TransactionTemplate slowWriter = new TransactionTemplate(transactionManager);
        TransactionTemplate fastWriter = new TransactionTemplate(transactionManager);
        fastWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> slowWriter.executeWithoutResult(status -> {
            Order stale = orders.findById(id).orElseThrow();
            fastWriter.executeWithoutResult(other -> {
                Order current = orders.findById(id).orElseThrow();
                current.addItem(OrderItem.builder().productId(2L).productName("Sock").productPrice(new BigDecimal("5.00")).quantity(2).build());
                orders.saveAndFlush(current);
            });
            stale.addItem(OrderItem.builder().productId(3L).productName("Hat").productPrice(new BigDecimal("7.00")).quantity(1).build());
            orders.saveAndFlush(stale);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // the winner's line and total are intact; the loser's line was rolled back with its transaction
        assertThat(jdbc.queryForObject("select total_amount from orders where order_id = ?", BigDecimal.class, id)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long.class, id)).isEqualTo(2);
    }
}

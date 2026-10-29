package com.ecommerce.order.integration;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderRepository against a real PostgreSQL 16 whose schema is built by the real
 * Flyway migrations, with Hibernate in {@code validate} mode: any drift between the
 * entities and V1/V2 (column types, names, nullability) fails the context here,
 * which the H2 {@code test} profile (create-drop, Flyway off) can never notice.
 * Each test runs in a rolled-back transaction; writes are flushed and the
 * persistence context cleared so assertions read what Postgres actually stored.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryIT extends ContainersBase {

    @Autowired private OrderRepository orderRepository;
    @Autowired private EntityManager entityManager;

    private static Order order(String customerId, OrderStatus status) {
        return Order.builder().customerId(customerId).status(status)
                .orderDate(LocalDateTime.now()).totalAmount(BigDecimal.ZERO).build();
    }

    private static OrderItem item(long productId, String price, int quantity) {
        return OrderItem.builder().productId(productId).productName("Product " + productId)
                .productPrice(new BigDecimal(price)).quantity(quantity).build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private long itemRowCount(long orderId) {
        return ((Number) entityManager.createNativeQuery("select count(*) from order_items where order_id = ?1")
                .setParameter(1, orderId).getSingleResult()).longValue();
    }

    @Test
    void schemaIsBuiltByFlywayMigrations() {
        @SuppressWarnings("unchecked")
        List<String> versions = entityManager.createNativeQuery(
                "select version from flyway_schema_history where success order by installed_rank").getResultList();

        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    void save_cascadesItemsAndRoundTripsColumns() {
        LocalDateTime written = LocalDateTime.of(2026, 10, 29, 12, 0, 0, 123_456_000);
        Order order = order("cust-a", OrderStatus.CONFIRMED);
        order.setOrderDate(written);
        order.setBillingAddress("Billing street 1");
        order.setShippingAddress("Shipping street 2");
        order.addItem(item(1L, "29.99", 2));
        order.addItem(item(2L, "0.0001", 3));
        Long id = orderRepository.saveAndFlush(order).getOrderId();
        entityManager.clear();

        Order loaded = orderRepository.findById(id).orElseThrow();

        assertThat(id).isNotNull();
        assertThat(itemRowCount(id)).isEqualTo(2);
        assertThat(loaded.getOrderItems()).extracting(OrderItem::getOrderItemId).doesNotContainNull();
        assertThat(loaded.getOrderItems()).extracting(OrderItem::getProductId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("59.9803");
        assertThat(loaded.getTotalAmount().scale()).isEqualTo(4);
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(loaded.getBillingAddress()).isEqualTo("Billing street 1");
        assertThat(loaded.getShippingAddress()).isEqualTo("Shipping street 2");
        // Postgres TIMESTAMP keeps microsecond precision.
        assertThat(loaded.getOrderDate()).isEqualTo(written);
    }

    @Test
    void status_isStoredAsEnumName() {
        Long id = orderRepository.saveAndFlush(order("cust-b", OrderStatus.SHIPPED)).getOrderId();

        Object stored = entityManager.createNativeQuery("select status from orders where order_id = ?1")
                .setParameter(1, id).getSingleResult();

        assertThat(stored).isEqualTo("SHIPPED");
    }

    @Test
    void money_isRoundedToFourDecimalPlaces() {
        Order order = order("cust-c", OrderStatus.PENDING);
        order.setTotalAmount(new BigDecimal("1.23456"));
        Long id = orderRepository.saveAndFlush(order).getOrderId();
        entityManager.clear();

        assertThat(orderRepository.findById(id).orElseThrow().getTotalAmount()).isEqualTo(new BigDecimal("1.2346"));
    }

    @Test
    void findByCustomerId_returnsOnlyThatCustomersOrdersWithItems() {
        Order first = order("cust-d", OrderStatus.PENDING);
        first.addItem(item(1L, "10.00", 1));
        orderRepository.save(first);
        orderRepository.save(order("cust-d", OrderStatus.PAID));
        orderRepository.save(order("someone-else", OrderStatus.PENDING));
        flushAndClear();

        List<Order> orders = orderRepository.findByCustomerId("cust-d");

        assertThat(orders).hasSize(2).allSatisfy(o -> assertThat(o.getCustomerId()).isEqualTo("cust-d"));
        assertThat(orders).extracting(o -> o.getOrderItems().size()).containsExactlyInAnyOrder(1, 0);
        assertThat(orderRepository.findByCustomerId("nobody")).isEmpty();
    }

    @Test
    void removingItemFromOrder_deletesOrphanRow() {
        Order order = order("cust-e", OrderStatus.PENDING);
        order.addItem(item(1L, "10.00", 1));
        order.addItem(item(2L, "20.00", 1));
        Long id = orderRepository.saveAndFlush(order).getOrderId();
        entityManager.clear();

        Order loaded = orderRepository.findById(id).orElseThrow();
        loaded.getOrderItems().removeIf(i -> i.getProductId() == 1L);
        orderRepository.saveAndFlush(loaded);

        assertThat(itemRowCount(id)).isEqualTo(1);
    }

    @Test
    void deletingOrder_cascadesToItems() {
        Order order = order("cust-f", OrderStatus.CANCELLED);
        order.addItem(item(1L, "10.00", 1));
        order.addItem(item(2L, "20.00", 1));
        Long id = orderRepository.saveAndFlush(order).getOrderId();
        entityManager.clear();

        orderRepository.deleteById(id);
        flushAndClear();

        assertThat(orderRepository.findById(id)).isEmpty();
        assertThat(itemRowCount(id)).isZero();
    }

    @Test
    void foreignKey_onDeleteCascade_removesItemsWhenOrderRowIsDeleted() {
        Order order = order("cust-g", OrderStatus.CANCELLED);
        order.addItem(item(1L, "10.00", 1));
        Long id = orderRepository.saveAndFlush(order).getOrderId();
        entityManager.clear();

        entityManager.createNativeQuery("delete from orders where order_id = ?1").setParameter(1, id).executeUpdate();

        assertThat(itemRowCount(id)).isZero();
    }
}

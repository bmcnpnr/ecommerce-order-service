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

        assertThat(versions).containsExactly("1", "2", "3", "4", "5");
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

        List<Order> orders = orderRepository.findByCustomerId("cust-d", org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(orders).hasSize(2).allSatisfy(o -> assertThat(o.getCustomerId()).isEqualTo("cust-d"));
        assertThat(orders).extracting(o -> o.getOrderItems().size()).containsExactlyInAnyOrder(1, 0);
        assertThat(orderRepository.findByCustomerId("nobody", org.springframework.data.domain.PageRequest.of(0, 100))).isEmpty();
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

    // ---- last change (V5) and the abandoned-cart query ------------------------------------------

    private void setUpdatedAt(long orderId, LocalDateTime when) {
        entityManager.createNativeQuery("update orders set updated_at = ?1 where order_id = ?2")
                .setParameter(1, when).setParameter(2, orderId).executeUpdate();
    }

    private LocalDateTime updatedAtInTheDatabase(long orderId) {
        Object value = entityManager.createNativeQuery("select updated_at from orders where order_id = ?1")
                .setParameter(1, orderId).getSingleResult();
        return value instanceof LocalDateTime time ? time : ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private long savedAt(OrderStatus status, LocalDateTime lastChange) {
        long id = orderRepository.saveAndFlush(order("cust-idle", status)).getOrderId();
        setUpdatedAt(id, lastChange);
        return id;
    }

    @Test
    void updatedAt_isSetOnInsert_movesWhenARowIsWritten_andStaysWhenNothingChanged() {
        long id = orderRepository.saveAndFlush(order("cust-touch", OrderStatus.PENDING)).getOrderId();
        LocalDateTime longAgo = LocalDateTime.now().minusDays(2).withNano(0);
        setUpdatedAt(id, longAgo);
        entityManager.clear();

        // nothing changed: Hibernate writes nothing, so the last-change time stays
        orderRepository.saveAndFlush(orderRepository.findById(id).orElseThrow());
        assertThat(updatedAtInTheDatabase(id)).isEqualTo(longAgo);
        entityManager.clear();

        // an item is added (the total changes): the row is written and the last-change time moves to now
        Order cart = orderRepository.findById(id).orElseThrow();
        cart.addItem(item(1L, "5.00", 2));
        orderRepository.saveAndFlush(cart);
        entityManager.clear();
        assertThat(updatedAtInTheDatabase(id)).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void findIdsIdleSince_returnsOnlyPendingOrdersIdleStrictlyBeforeTheCutoff_oldestFirst() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24).withNano(0);
        long oldest = savedAt(OrderStatus.PENDING, cutoff.minusDays(3));
        long older = savedAt(OrderStatus.PENDING, cutoff.minusHours(2));
        long justUnder = savedAt(OrderStatus.PENDING, cutoff.minusNanos(1_000));
        long exactlyAtCutoff = savedAt(OrderStatus.PENDING, cutoff);
        long fresh = savedAt(OrderStatus.PENDING, cutoff.plusHours(23));
        long confirmed = savedAt(OrderStatus.CONFIRMED, cutoff.minusDays(3));
        long paid = savedAt(OrderStatus.PAID, cutoff.minusDays(3));
        long cancelled = savedAt(OrderStatus.CANCELLED, cutoff.minusDays(3));
        List<Long> mine = List.of(oldest, older, justUnder, exactlyAtCutoff, fresh, confirmed, paid, cancelled);

        List<Long> idle = orderRepository.findIdsIdleSince(OrderStatus.PENDING, cutoff, org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(idle.stream().filter(mine::contains)).containsExactly(oldest, older, justUnder);
        assertThat(idle).doesNotContain(exactlyAtCutoff, fresh, confirmed, paid, cancelled);
    }

    @Test
    void findIdsIdleSince_isBounded_andThePagesFollowOnFromEachOther() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24).withNano(0);
        for (int i = 0; i < 5; i++) {
            savedAt(OrderStatus.PENDING, cutoff.minusHours(10 + i));
        }

        List<Long> all = orderRepository.findIdsIdleSince(OrderStatus.PENDING, cutoff, org.springframework.data.domain.PageRequest.of(0, 100));
        List<Long> firstTwo = orderRepository.findIdsIdleSince(OrderStatus.PENDING, cutoff, org.springframework.data.domain.PageRequest.of(0, 2));
        List<Long> nextTwo = orderRepository.findIdsIdleSince(OrderStatus.PENDING, cutoff, org.springframework.data.domain.PageRequest.of(1, 2));

        assertThat(firstTwo).hasSize(2).isEqualTo(all.subList(0, 2));
        assertThat(nextTwo).hasSize(2).isEqualTo(all.subList(2, 4));
    }
}

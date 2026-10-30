package com.ecommerce.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The oldest publishable rows, locked for this transaction. {@code SKIP LOCKED} lets a second
     * instance take the next rows instead of waiting (and never blocks on a lock), and the {@code ORDER BY id}
     * keeps the publish order of events that share a key.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE parked_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockBatch(@Param("limit") int limit);

    long countByParkedAtIsNull();

    long countByParkedAtIsNotNull();
}

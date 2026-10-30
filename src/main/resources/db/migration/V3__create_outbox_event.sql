-- Transactional outbox (R-2): events are written in the same transaction as the state change and
-- published to Kafka by OutboxRelay. A row exists until Kafka acknowledges it, then it is deleted.
-- parked_at marks a row that can never be published (too large, invalid topic, unserialisable);
-- unpark it after fixing the cause: UPDATE outbox_event SET parked_at = NULL WHERE id = ?
CREATE TABLE outbox_event (
    id         BIGSERIAL    PRIMARY KEY,
    topic      VARCHAR(255) NOT NULL,
    event_key  VARCHAR(255),
    payload    TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    attempts   INTEGER      NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    parked_at  TIMESTAMP
);
CREATE INDEX idx_outbox_event_pending ON outbox_event (parked_at, id);

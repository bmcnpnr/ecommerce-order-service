-- Optimistic lock for orders. Adding an item calls product-service between reading and writing the
-- order, outside any database transaction, so a database lock cannot cover it; the version turns a
-- concurrent add-item / confirm / cancel / payment event into a detectable conflict instead of a
-- silent lost update. Existing rows start at version 0; nothing else changes.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

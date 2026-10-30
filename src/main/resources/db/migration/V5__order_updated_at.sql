-- When an order last changed, so that ReservationExpiryJob can cancel PENDING carts nobody has touched for
-- orders.reservation.pending-ttl and give their reserved stock back. The application maintains it (insert and
-- every update); existing rows start at the time of this migration, so no cart expires sooner than one TTL
-- after the deploy.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- The job's query: the oldest idle orders of one status.
CREATE INDEX IF NOT EXISTS idx_orders_status_updated ON orders (status, updated_at);

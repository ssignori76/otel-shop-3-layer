ALTER TABLE orders ADD COLUMN user_id VARCHAR(36) NULL;
ALTER TABLE orders ADD COLUMN total_cents INT NOT NULL DEFAULT 0;
CREATE INDEX idx_orders_user_id ON orders (user_id);

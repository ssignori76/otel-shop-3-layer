CREATE TABLE IF NOT EXISTS orders (
    order_id VARCHAR(36) NOT NULL,
    journey_id VARCHAR(36) NOT NULL,
    cart_id VARCHAR(36) NOT NULL,
    payment_authorization_id VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id),
    UNIQUE INDEX uk_orders_journey_id (journey_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

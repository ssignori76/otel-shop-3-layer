ALTER TABLE cart ADD COLUMN user_id VARCHAR(36) NULL;
CREATE INDEX idx_cart_user_id ON cart (user_id);

CREATE TABLE IF NOT EXISTS products (
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(512) NOT NULL,
    price_cents INT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    category VARCHAR(64) NOT NULL,
    badge VARCHAR(64) NOT NULL,
    PRIMARY KEY (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cart_items (
    cart_id VARCHAR(36) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    unit_price_cents INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (cart_id, sku),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES cart (cart_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO products (sku, name, description, price_cents, currency, category, badge) VALUES
    ('SKU-WATCH-01', 'Telemetry Pro Watch', 'Smartwatch con sensori biometrici in tempo reale, cassa in titanio e autonomia di 7 giorni.', 24900, 'EUR', 'Wearable', 'Best Seller'),
    ('SKU-AUDIO-02', 'Cloud Mesh Headphones', 'Cuffie wireless over-ear con cancellazione attiva del rumore e audio spaziale ad alta fedelta.', 18900, 'EUR', 'Audio', 'Novita'),
    ('SKU-HUB-03', 'Edge Observability Hub', 'Mini-server compatto per domotica e telemetria domestica con porta 2.5GbE e storage NVMe.', 32900, 'EUR', 'Smart Home', 'Edizione Pro');

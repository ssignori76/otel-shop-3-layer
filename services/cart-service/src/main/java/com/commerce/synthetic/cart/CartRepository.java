package com.commerce.synthetic.cart;

import com.commerce.synthetic.api.CartItemResponse;
import com.commerce.synthetic.api.CartResponse;
import com.commerce.synthetic.api.CreateCartRequest;
import com.commerce.synthetic.api.ProductResponse;
import com.commerce.synthetic.scenario.JourneyState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CartRepository {

    private final JdbcTemplate jdbcTemplate;

    public CartRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listProducts() {
        return jdbcTemplate.query(
                "SELECT sku, name, description, price_cents, currency, category, badge FROM products ORDER BY sku ASC",
                (rs, rowNum) -> new ProductResponse(
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("price_cents"),
                        rs.getString("currency"),
                        rs.getString("category"),
                        rs.getString("badge")
                )
        );
    }

    @Transactional(readOnly = true)
    public Optional<ProductResponse> findProductBySku(String sku) {
        List<ProductResponse> rows = jdbcTemplate.query(
                "SELECT sku, name, description, price_cents, currency, category, badge FROM products WHERE sku = ?",
                (rs, rowNum) -> new ProductResponse(
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("price_cents"),
                        rs.getString("currency"),
                        rs.getString("category"),
                        rs.getString("badge")
                ),
                sku
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public CartResponse createCart(CreateCartRequest request) {
        return createCart(request, null);
    }

    @Transactional
    public CartResponse createCart(CreateCartRequest request, String userId) {
        String cartId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO cart (cart_id, journey_id, status, user_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                cartId,
                request.journeyId(),
                JourneyState.CART_CREATED.name(),
                userId,
                ts,
                ts
        );

        int unitPriceCents = findProductBySku(request.sku())
                .map(ProductResponse::priceCents)
                .orElse(1990);
        int qty = Math.max(1, request.quantity());
        jdbcTemplate.update(
                "INSERT INTO cart_items (cart_id, sku, quantity, unit_price_cents, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                cartId,
                request.sku(),
                qty,
                unitPriceCents,
                ts,
                ts
        );

        List<CartItemResponse> items = loadCartItems(cartId);
        int totalCents = items.stream().mapToInt(CartItemResponse::lineTotalCents).sum();
        return new CartResponse(
                cartId,
                request.journeyId(),
                JourneyState.CART_CREATED.name(),
                now.toString(),
                now.toString(),
                userId,
                items,
                totalCents
        );
    }

    @Transactional
    public Optional<CartResponse> readCart(String cartId) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                "UPDATE cart SET status = ?, updated_at = ? WHERE cart_id = ? AND status <> 'CHECKED_OUT'",
                JourneyState.CART_READ.name(),
                Timestamp.from(now),
                cartId
        );
        if (updated == 0) {
            return loadCartById(cartId);
        }
        return loadCartById(cartId);
    }

    @Transactional(readOnly = true)
    public Optional<CartResponse> findActiveCartForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        List<String> ids = jdbcTemplate.query(
                "SELECT cart_id FROM cart WHERE user_id = ? AND status IN ('CART_CREATED', 'CART_READ') ORDER BY updated_at DESC",
                (rs, rowNum) -> rs.getString("cart_id"),
                userId
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return loadCartById(ids.get(0));
    }

    @Transactional
    public Optional<CartResponse> addItem(String cartId, String userId, String sku, int quantity) {
        Optional<CartResponse> existingCart = loadCartById(cartId);
        if (existingCart.isEmpty()) {
            return Optional.empty();
        }
        if (userId != null && existingCart.get().userId() != null && !userId.equals(existingCart.get().userId())) {
            return Optional.empty();
        }
        Optional<ProductResponse> product = findProductBySku(sku);
        if (product.isEmpty()) {
            throw new IllegalArgumentException("Unknown product SKU: " + sku);
        }

        Timestamp now = Timestamp.from(Instant.now());
        List<Integer> existingQty = jdbcTemplate.query(
                "SELECT quantity FROM cart_items WHERE cart_id = ? AND sku = ?",
                (rs, rowNum) -> rs.getInt("quantity"),
                cartId, sku
        );
        if (existingQty.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO cart_items (cart_id, sku, quantity, unit_price_cents, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    cartId, sku, quantity, product.get().priceCents(), now, now
            );
        } else {
            int newQty = Math.min(99, existingQty.get(0) + quantity);
            jdbcTemplate.update(
                    "UPDATE cart_items SET quantity = ?, unit_price_cents = ?, updated_at = ? WHERE cart_id = ? AND sku = ?",
                    newQty, product.get().priceCents(), now, cartId, sku
            );
        }
        jdbcTemplate.update("UPDATE cart SET updated_at = ? WHERE cart_id = ?", now, cartId);
        return loadCartById(cartId);
    }

    @Transactional
    public Optional<CartResponse> removeItem(String cartId, String userId, String sku) {
        Optional<CartResponse> existingCart = loadCartById(cartId);
        if (existingCart.isEmpty()) {
            return Optional.empty();
        }
        if (userId != null && existingCart.get().userId() != null && !userId.equals(existingCart.get().userId())) {
            return Optional.empty();
        }
        jdbcTemplate.update("DELETE FROM cart_items WHERE cart_id = ? AND sku = ?", cartId, sku);
        jdbcTemplate.update("UPDATE cart SET updated_at = ? WHERE cart_id = ?", Timestamp.from(Instant.now()), cartId);
        return loadCartById(cartId);
    }

    @Transactional
    public Optional<CartResponse> markCheckedOut(String cartId, String userId) {
        Optional<CartResponse> existingCart = loadCartById(cartId);
        if (existingCart.isEmpty()) {
            return Optional.empty();
        }
        if (userId != null && existingCart.get().userId() != null && !userId.equals(existingCart.get().userId())) {
            return Optional.empty();
        }
        jdbcTemplate.update(
                "UPDATE cart SET status = 'CHECKED_OUT', updated_at = ? WHERE cart_id = ?",
                Timestamp.from(Instant.now()), cartId
        );
        return loadCartById(cartId);
    }

    private Optional<CartResponse> loadCartById(String cartId) {
        List<CartResponse> rows = jdbcTemplate.query(
                "SELECT cart_id, journey_id, status, user_id, created_at, updated_at FROM cart WHERE cart_id = ?",
                (rs, rowNum) -> {
                    String id = rs.getString("cart_id");
                    List<CartItemResponse> items = loadCartItems(id);
                    int total = items.stream().mapToInt(CartItemResponse::lineTotalCents).sum();
                    return new CartResponse(
                            id,
                            rs.getString("journey_id"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant().toString(),
                            rs.getTimestamp("updated_at").toInstant().toString(),
                            rs.getString("user_id"),
                            items,
                            total
                    );
                },
                cartId
        );
        return rows.stream().findFirst();
    }

    private List<CartItemResponse> loadCartItems(String cartId) {
        return jdbcTemplate.query(
                "SELECT ci.sku, COALESCE(p.name, ci.sku) AS product_name, ci.quantity, ci.unit_price_cents " +
                        "FROM cart_items ci LEFT JOIN products p ON p.sku = ci.sku " +
                        "WHERE ci.cart_id = ? ORDER BY ci.sku ASC",
                (rs, rowNum) -> {
                    int qty = rs.getInt("quantity");
                    int unit = rs.getInt("unit_price_cents");
                    return new CartItemResponse(
                            rs.getString("sku"),
                            rs.getString("product_name"),
                            qty,
                            unit,
                            qty * unit
                    );
                },
                cartId
        );
    }
}

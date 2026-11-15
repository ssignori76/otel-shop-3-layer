package com.commerce.synthetic.cart;

import com.commerce.synthetic.api.CartResponse;
import com.commerce.synthetic.api.CreateCartRequest;
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

    @Transactional
    public CartResponse createCart(CreateCartRequest request) {
        String cartId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO cart (cart_id, journey_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                cartId,
                request.journeyId(),
                JourneyState.CART_CREATED.name(),
                ts,
                ts
        );
        return new CartResponse(
                cartId,
                request.journeyId(),
                JourneyState.CART_CREATED.name(),
                now.toString(),
                now.toString()
        );
    }

    @Transactional
    public Optional<CartResponse> readCart(String cartId) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                "UPDATE cart SET status = ?, updated_at = ? WHERE cart_id = ?",
                JourneyState.CART_READ.name(),
                Timestamp.from(now),
                cartId
        );
        if (updated == 0) {
            return Optional.empty();
        }
        List<CartResponse> rows = jdbcTemplate.query(
                "SELECT cart_id, journey_id, status, created_at, updated_at FROM cart WHERE cart_id = ?",
                (rs, rowNum) -> new CartResponse(
                        rs.getString("cart_id"),
                        rs.getString("journey_id"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getTimestamp("updated_at").toInstant().toString()
                ),
                cartId
        );
        return rows.stream().findFirst();
    }
}

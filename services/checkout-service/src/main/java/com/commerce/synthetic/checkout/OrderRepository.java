package com.commerce.synthetic.checkout;

import com.commerce.synthetic.api.OrderSummaryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes a pre-checkout DB verification query. Supports normal fast query, DB_SLOW, and real JDBC DB_TIMEOUT.
     */
    public void verifyDatabaseAndApplyDbScenario(boolean slowScenario, long slowDurationMs, boolean timeoutScenario, int timeoutMs) throws SQLException {
        if (timeoutScenario) {
            int queryTimeoutSeconds = Math.max(1, timeoutMs / 1000);
            double sleepSeconds = queryTimeoutSeconds + 2.0;
            jdbcTemplate.execute((Connection conn) -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT SLEEP(?)")) {
                    ps.setQueryTimeout(queryTimeoutSeconds);
                    ps.setDouble(1, sleepSeconds);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                return null;
            });
            return;
        }

        if (slowScenario) {
            double sleepSeconds = Math.max(0.1, slowDurationMs / 1000.0);
            jdbcTemplate.execute((Connection conn) -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT SLEEP(?)")) {
                    ps.setDouble(1, sleepSeconds);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                return null;
            });
            return;
        }

        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }

    @Transactional
    public void insertOrder(String orderId, String journeyId, String cartId, String paymentAuthId, String status) {
        insertOrder(orderId, journeyId, cartId, paymentAuthId, status, null, 0);
    }

    @Transactional
    public void insertOrder(String orderId, String journeyId, String cartId, String paymentAuthId, String status, String userId, int totalCents) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO orders (order_id, journey_id, cart_id, payment_authorization_id, status, user_id, total_cents, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                journeyId,
                cartId,
                paymentAuthId,
                status,
                userId,
                Math.max(0, totalCents),
                now,
                now
        );
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> findOrdersByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT order_id, journey_id, cart_id, payment_authorization_id, status, total_cents, created_at, updated_at " +
                        "FROM orders WHERE user_id = ? ORDER BY created_at DESC LIMIT 50",
                (rs, rowNum) -> new OrderSummaryResponse(
                        rs.getString("order_id"),
                        rs.getString("journey_id"),
                        rs.getString("cart_id"),
                        rs.getString("payment_authorization_id"),
                        rs.getString("status"),
                        rs.getInt("total_cents"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getTimestamp("updated_at").toInstant().toString()
                ),
                userId
        );
    }

    @Transactional
    public void updateOrderStatus(String orderId, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "UPDATE orders SET status = ?, updated_at = ? WHERE order_id = ?",
                status,
                now,
                orderId
        );
    }
}

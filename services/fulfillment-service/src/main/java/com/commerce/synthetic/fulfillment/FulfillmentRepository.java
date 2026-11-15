package com.commerce.synthetic.fulfillment;

import com.commerce.synthetic.scenario.JourneyState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class FulfillmentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public FulfillmentRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Persists a fulfillment record inside a dedicated local JDBC transaction and returns the fulfillment_id.
     * Idempotent on duplicate order_id.
     */
    public String persistFulfillmentCommitted(String orderId, String journeyId) {
        return transactionTemplate.execute(status -> {
            List<String> existing = jdbcTemplate.query(
                    "SELECT fulfillment_id FROM fulfillment WHERE order_id = ?",
                    (rs, rowNum) -> rs.getString("fulfillment_id"),
                    orderId
            );
            if (!existing.isEmpty()) {
                return existing.get(0);
            }

            String fulfillmentId = UUID.randomUUID().toString();
            Timestamp now = Timestamp.from(Instant.now());
            try {
                jdbcTemplate.update(
                        "INSERT INTO fulfillment (fulfillment_id, order_id, journey_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                        fulfillmentId,
                        orderId,
                        journeyId,
                        JourneyState.FULFILLED.name(),
                        now,
                        now
                );
                return fulfillmentId;
            } catch (DuplicateKeyException dupEx) {
                List<String> afterDup = jdbcTemplate.query(
                        "SELECT fulfillment_id FROM fulfillment WHERE order_id = ?",
                        (rs, rowNum) -> rs.getString("fulfillment_id"),
                        orderId
                );
                if (!afterDup.isEmpty()) {
                    return afterDup.get(0);
                }
                throw dupEx;
            }
        });
    }
}

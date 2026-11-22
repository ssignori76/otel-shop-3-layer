package com.commerce.synthetic.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdTs = rs.getTimestamp("created_at");
        String createdAt = createdTs != null ? createdTs.toInstant().toString() : Instant.now().toString();
        return new UserRecord(
                rs.getString("user_id"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                createdAt
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UserRecord createUser(String email, String fullName, String passwordHash) {
        String userId = UUID.randomUUID().toString();
        String normalizedEmail = normalizeEmail(email);
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO users (user_id, email, full_name, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, normalizedEmail, fullName.trim(), passwordHash, ts, ts
        );
        return new UserRecord(userId, normalizedEmail, fullName.trim(), passwordHash, now.toString());
    }

    @Transactional(readOnly = true)
    public Optional<UserRecord> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        try {
            UserRecord found = jdbcTemplate.queryForObject(
                    "SELECT user_id, email, full_name, password_hash, created_at FROM users WHERE email = ?",
                    ROW_MAPPER,
                    normalizeEmail(email)
            );
            return Optional.ofNullable(found);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<UserRecord> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            UserRecord found = jdbcTemplate.queryForObject(
                    "SELECT user_id, email, full_name, password_hash, created_at FROM users WHERE user_id = ?",
                    ROW_MAPPER,
                    userId
            );
            return Optional.ofNullable(found);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Transactional
    public boolean updatePasswordByEmail(String email, String newPasswordHash) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int updated = jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE email = ?",
                newPasswordHash,
                Timestamp.from(Instant.now()),
                normalizeEmail(email)
        );
        return updated > 0;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

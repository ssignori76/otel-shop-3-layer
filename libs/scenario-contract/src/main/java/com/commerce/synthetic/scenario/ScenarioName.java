package com.commerce.synthetic.scenario;

import java.util.Locale;

/**
 * Canonical synthetic scenarios defined in Section 14 of the specification.
 */
public enum ScenarioName {
    NORMAL,
    DB_SLOW,
    BE_ERROR,
    DB_TIMEOUT,
    MERCHANT_TIMEOUT,
    BROKER_UNAVAILABLE,
    FULFILLMENT_ERROR;

    public static ScenarioName fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ScenarioName value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported scenario name: " + raw);
    }
}

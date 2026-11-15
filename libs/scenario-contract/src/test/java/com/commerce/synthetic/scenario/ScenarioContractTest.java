package com.commerce.synthetic.scenario;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioContractTest {

    @AfterEach
    void tearDown() {
        TelemetryHelper.clearMdc();
    }

    @Test
    void scenarioNameParsesCaseInsensitivelyAndDefaultsToNormal() {
        assertThat(ScenarioName.fromValue(null)).isEqualTo(ScenarioName.NORMAL);
        assertThat(ScenarioName.fromValue("  ")).isEqualTo(ScenarioName.NORMAL);
        assertThat(ScenarioName.fromValue("db_slow")).isEqualTo(ScenarioName.DB_SLOW);
        assertThat(ScenarioName.fromValue("MERCHANT_TIMEOUT")).isEqualTo(ScenarioName.MERCHANT_TIMEOUT);

        assertThatThrownBy(() -> ScenarioName.fromValue("INVALID_SCENARIO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported scenario name");
    }

    @Test
    void scenarioContextForcesNormalWhenDisabled() {
        ScenarioContext disabled = ScenarioContext.resolve(
                "run-1", "journey-1", "DB_TIMEOUT", "false", "NORMAL", false
        );
        assertThat(disabled.scenarioName()).isEqualTo(ScenarioName.DB_TIMEOUT);
        assertThat(disabled.scenarioEnabled()).isFalse();
        assertThat(disabled.effectiveScenario()).isEqualTo(ScenarioName.NORMAL);
        assertThat(disabled.isScenarioActive(ScenarioName.DB_TIMEOUT)).isFalse();

        ScenarioContext enabled = ScenarioContext.resolve(
                "run-2", "journey-2", "DB_TIMEOUT", "true", "NORMAL", false
        );
        assertThat(enabled.effectiveScenario()).isEqualTo(ScenarioName.DB_TIMEOUT);
        assertThat(enabled.isScenarioActive(ScenarioName.DB_TIMEOUT)).isTrue();
        assertThat(enabled.isScenarioActive(ScenarioName.BE_ERROR)).isFalse();
    }

    @Test
    void journeyStateEnforcesCanonicalStateMachine() {
        assertThat(JourneyState.STARTED.canTransitionTo(JourneyState.PAGE_LOADED)).isTrue();
        assertThat(JourneyState.PAGE_LOADED.canTransitionTo(JourneyState.CART_CREATED)).isTrue();
        assertThat(JourneyState.CART_CREATED.canTransitionTo(JourneyState.CART_READ)).isTrue();
        assertThat(JourneyState.CART_READ.canTransitionTo(JourneyState.PAYMENT_AUTHORIZED)).isTrue();
        assertThat(JourneyState.PAYMENT_AUTHORIZED.canTransitionTo(JourneyState.ORDER_PUBLISHED)).isTrue();
        assertThat(JourneyState.ORDER_PUBLISHED.canTransitionTo(JourneyState.FULFILLED)).isTrue();
        assertThat(JourneyState.FULFILLED.canTransitionTo(JourneyState.STARTED)).isFalse();

        // Any active state can transition to a failure state, but failure states are terminal
        assertThat(JourneyState.CART_READ.canTransitionTo(JourneyState.DB_TIMEOUT)).isTrue();
        assertThat(JourneyState.DB_TIMEOUT.isFailureState()).isTrue();
        assertThat(JourneyState.DB_TIMEOUT.canTransitionTo(JourneyState.FULFILLED)).isFalse();
    }

    @Test
    void telemetryHelperBindsMdcAndRecordsMetricsAndErrors() {
        ScenarioContext ctx = new ScenarioContext("run-123", "journey-456", ScenarioName.DB_SLOW, true);
        TelemetryHelper.bindContext(ctx, JourneyState.STARTED);
        assertThat(MDC.get("run_id")).isEqualTo("run-123");
        assertThat(MDC.get("journey_id")).isEqualTo("journey-456");
        assertThat(MDC.get("scenario")).isEqualTo("DB_SLOW");
        assertThat(MDC.get("effective_scenario")).isEqualTo("DB_SLOW");
        assertThat(MDC.get("journey_state")).isEqualTo("STARTED");

        TelemetryHelper.bindEntityIds("cart-1", "order-1", "fulfill-1");
        assertThat(MDC.get("cart_id")).isEqualTo("cart-1");
        assertThat(MDC.get("order_id")).isEqualTo("order-1");
        assertThat(MDC.get("fulfillment_id")).isEqualTo("fulfill-1");

        TelemetryHelper.updateState(JourneyState.CART_CREATED);
        assertThat(MDC.get("journey_state")).isEqualTo("CART_CREATED");

        ScenarioException ex = new ScenarioException(
                ScenarioName.BE_ERROR,
                JourneyState.CHECKOUT_FAILED,
                500,
                "BE_ERROR",
                "Synthetic backend error"
        );
        TelemetryHelper.recordError(ex.getTerminalState(), ex.getErrorCode(), ex);
        assertThat(MDC.get("journey_state")).isEqualTo("CHECKOUT_FAILED");
        assertThat(MDC.get("error_code")).isEqualTo("BE_ERROR");

        TelemetryHelper.incrementCounter("commerce.test.counter", "{event}", "Test counter", Map.of("scenario", "NORMAL"));
        TelemetryHelper.recordHistogram("commerce.test.duration", "ms", "Test histogram", 12.5, Map.of("scenario", "NORMAL"));
    }
}

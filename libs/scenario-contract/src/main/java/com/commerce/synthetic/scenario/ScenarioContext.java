package com.commerce.synthetic.scenario;

import java.util.Objects;

/**
 * Immutable correlation and scenario execution context for a business journey.
 */
public record ScenarioContext(
        String runId,
        String journeyId,
        ScenarioName scenarioName,
        boolean scenarioEnabled
) {
    public ScenarioContext {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(journeyId, "journeyId must not be null");
        scenarioName = scenarioName == null ? ScenarioName.NORMAL : scenarioName;
    }

    public static ScenarioContext resolve(
            String runId,
            String journeyId,
            String headerScenario,
            String headerEnabled,
            String defaultScenario,
            boolean defaultEnabled
    ) {
        ScenarioName resolvedScenario = (headerScenario != null && !headerScenario.isBlank())
                ? ScenarioName.fromValue(headerScenario)
                : ScenarioName.fromValue(defaultScenario);

        boolean resolvedEnabled = (headerEnabled != null && !headerEnabled.isBlank())
                ? Boolean.parseBoolean(headerEnabled.trim())
                : defaultEnabled;

        return new ScenarioContext(runId, journeyId, resolvedScenario, resolvedEnabled);
    }

    /**
     * Per Section 14: SCENARIO_ENABLED=false causes the normal path even when a non-normal scenario name is configured.
     */
    public ScenarioName effectiveScenario() {
        return scenarioEnabled ? scenarioName : ScenarioName.NORMAL;
    }

    public boolean isScenarioActive(ScenarioName target) {
        return scenarioEnabled && scenarioName == target;
    }
}

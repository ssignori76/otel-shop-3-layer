package com.commerce.synthetic.generator;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JourneyExecutionRecord(
        @JsonProperty("run_id") String runId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("cart_id") String cartId,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("scenario_name") String scenarioName,
        @JsonProperty("scenario_enabled") boolean scenarioEnabled,
        @JsonProperty("effective_scenario") String effectiveScenario,
        @JsonProperty("http_status") int httpStatus,
        @JsonProperty("state") String state,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("started_at_ms") long startedAtMs,
        @JsonProperty("completed_at_ms") long completedAtMs,
        @JsonProperty("duration_ms") double durationMs,
        @JsonProperty("delay_since_previous_completion_ms") long delaySincePreviousCompletionMs
) {
}

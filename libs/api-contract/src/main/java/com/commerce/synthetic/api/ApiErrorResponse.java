package com.commerce.synthetic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Stable synchronous error JSON envelope required by Section 9.3 of the specification.
 */
public record ApiErrorResponse(
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("status") int status,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("message") String message,
        @JsonProperty("run_id") String runId,
        @JsonProperty("journey_id") String journeyId,
        @JsonProperty("trace_id") String traceId
) {
    public static ApiErrorResponse of(
            int status,
            String errorCode,
            String message,
            String runId,
            String journeyId,
            String traceId
    ) {
        return new ApiErrorResponse(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                status,
                errorCode,
                message,
                runId != null ? runId : "",
                journeyId != null ? journeyId : "",
                traceId != null ? traceId : ""
        );
    }
}

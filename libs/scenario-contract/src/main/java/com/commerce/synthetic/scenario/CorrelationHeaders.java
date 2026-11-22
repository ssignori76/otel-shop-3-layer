package com.commerce.synthetic.scenario;

import java.util.List;

/**
 * HTTP and AMQP header names required by Section 9.1 of the specification.
 */
public final class CorrelationHeaders {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String BAGGAGE = "baggage";
    public static final String RUN_ID = "x-run-id";
    public static final String JOURNEY_ID = "x-journey-id";
    public static final String SIMULATION_SCENARIO = "x-simulation-scenario";
    public static final String SIMULATION_ENABLED = "x-simulation-enabled";
    public static final String USER_ID = "x-user-id";
    public static final String USER_EMAIL = "x-user-email";

    public static final List<String> PROPAGATED_HEADERS = List.of(
            TRACEPARENT,
            TRACESTATE,
            BAGGAGE,
            RUN_ID,
            JOURNEY_ID,
            SIMULATION_SCENARIO,
            SIMULATION_ENABLED,
            USER_ID,
            USER_EMAIL
    );

    private CorrelationHeaders() {
    }
}

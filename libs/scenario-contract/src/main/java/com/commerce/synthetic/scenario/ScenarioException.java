package com.commerce.synthetic.scenario;

/**
 * Deterministic exception thrown when a configured synthetic fault scenario triggers.
 */
public class ScenarioException extends RuntimeException {

    private final ScenarioName scenario;
    private final JourneyState terminalState;
    private final int httpStatus;
    private final String errorCode;

    public ScenarioException(
            ScenarioName scenario,
            JourneyState terminalState,
            int httpStatus,
            String errorCode,
            String message
    ) {
        super(message);
        this.scenario = scenario;
        this.terminalState = terminalState;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ScenarioException(
            ScenarioName scenario,
            JourneyState terminalState,
            int httpStatus,
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.scenario = scenario;
        this.terminalState = terminalState;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ScenarioName getScenario() {
        return scenario;
    }

    public JourneyState getTerminalState() {
        return terminalState;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

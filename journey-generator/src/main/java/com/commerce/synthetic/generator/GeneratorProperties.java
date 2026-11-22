package com.commerce.synthetic.generator;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated configuration properties for Journey Generator per Section 8 of the specification.
 */
@ConfigurationProperties(prefix = "commerce.generator")
public class GeneratorProperties {

    private boolean enabled = true;
    private int intervalSeconds = 10;
    private int initialDelaySeconds = 5;
    private int maxInFlight = 1;
    private int requestTimeoutSeconds = 30;
    private int maxJourneys = 0;
    private boolean stopOnUnexpectedError = false;
    private String targetBaseUrl = "http://apache-web:80";
    private String scenarioName = "NORMAL";
    private boolean scenarioEnabled = false;
    private boolean authEnabled = true;
    private String authEmail = "synthetic@otelshop.local";
    private String authFullName = "Synthetic Journey Bot";
    private String authPassword = "SynthPass123!";
    private String staticToken = "";

    @PostConstruct
    public void validate() {
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("GENERATOR_INTERVAL_SECONDS must be >= 1, got: " + intervalSeconds);
        }
        if (initialDelaySeconds < 0) {
            throw new IllegalArgumentException("GENERATOR_INITIAL_DELAY_SECONDS must be >= 0, got: " + initialDelaySeconds);
        }
        if (maxInFlight != 1) {
            throw new IllegalArgumentException("GENERATOR_MAX_IN_FLIGHT is fixed to 1 in the initial release, got: " + maxInFlight);
        }
        if (requestTimeoutSeconds < 1) {
            throw new IllegalArgumentException("GENERATOR_REQUEST_TIMEOUT_SECONDS must be >= 1, got: " + requestTimeoutSeconds);
        }
        if (maxJourneys < 0) {
            throw new IllegalArgumentException("GENERATOR_MAX_JOURNEYS must be >= 0, got: " + maxJourneys);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public int getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    public void setInitialDelaySeconds(int initialDelaySeconds) {
        this.initialDelaySeconds = initialDelaySeconds;
    }

    public int getMaxInFlight() {
        return maxInFlight;
    }

    public void setMaxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxJourneys() {
        return maxJourneys;
    }

    public void setMaxJourneys(int maxJourneys) {
        this.maxJourneys = maxJourneys;
    }

    public boolean isStopOnUnexpectedError() {
        return stopOnUnexpectedError;
    }

    public void setStopOnUnexpectedError(boolean stopOnUnexpectedError) {
        this.stopOnUnexpectedError = stopOnUnexpectedError;
    }

    public String getTargetBaseUrl() {
        return targetBaseUrl;
    }

    public void setTargetBaseUrl(String targetBaseUrl) {
        this.targetBaseUrl = targetBaseUrl;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public boolean isScenarioEnabled() {
        return scenarioEnabled;
    }

    public void setScenarioEnabled(boolean scenarioEnabled) {
        this.scenarioEnabled = scenarioEnabled;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public String getAuthEmail() {
        return authEmail;
    }

    public void setAuthEmail(String authEmail) {
        this.authEmail = authEmail;
    }

    public String getAuthFullName() {
        return authFullName;
    }

    public void setAuthFullName(String authFullName) {
        this.authFullName = authFullName;
    }

    public String getAuthPassword() {
        return authPassword;
    }

    public void setAuthPassword(String authPassword) {
        this.authPassword = authPassword;
    }

    public String getStaticToken() {
        return staticToken;
    }

    public void setStaticToken(String staticToken) {
        this.staticToken = staticToken;
    }
}

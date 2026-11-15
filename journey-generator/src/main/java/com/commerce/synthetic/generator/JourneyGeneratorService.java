package com.commerce.synthetic.generator;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.JourneyResponse;
import com.commerce.synthetic.api.JourneyStartRequest;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.ScenarioName;
import com.commerce.synthetic.scenario.TelemetryHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class JourneyGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(JourneyGeneratorService.class);

    private final GeneratorProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String defaultRunId = UUID.randomUUID().toString();
    private final ReentrantLock inFlightLock = new ReentrantLock(true);
    private final AtomicBoolean activeInFlight = new AtomicBoolean(false);
    private final AtomicInteger executedCount = new AtomicInteger(0);
    private final AtomicLong lastCompletedAtMs = new AtomicLong(0L);
    private final List<JourneyExecutionRecord> history = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;

    @Autowired
    public JourneyGeneratorService(GeneratorProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildDefaultRestClient(properties));
    }

    public JourneyGeneratorService(GeneratorProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static RestClient buildDefaultRestClient(GeneratorProperties properties) {
        int timeoutMs = properties.getRequestTimeoutSeconds() * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .baseUrl(properties.getTargetBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @PostConstruct
    public void startSchedulerIfEnabled() {
        if (!properties.isEnabled()) {
            log.info("Journey Generator scheduler is disabled by configuration (GENERATOR_ENABLED=false)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "journey-generator-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::runScheduledTick,
                properties.getInitialDelaySeconds(),
                properties.getIntervalSeconds(),
                TimeUnit.SECONDS
        );
        log.info("Journey Generator started fixed-delay scheduler: initial_delay={}s interval={}s run_id={}",
                properties.getInitialDelaySeconds(), properties.getIntervalSeconds(), defaultRunId);
    }

    @PreDestroy
    public void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void runScheduledTick() {
        int maxJourneys = properties.getMaxJourneys();
        if (maxJourneys > 0 && executedCount.get() >= maxJourneys) {
            log.info("Reached GENERATOR_MAX_JOURNEYS={}, stopping scheduled execution", maxJourneys);
            stopScheduler();
            return;
        }

        JourneyExecutionRecord record = executeSingleJourney(
                defaultRunId,
                UUID.randomUUID().toString(),
                properties.getScenarioName(),
                properties.isScenarioEnabled()
        );

        if (properties.isStopOnUnexpectedError() && "INTERNAL_FAILURE".equals(record.state())) {
            log.error("Stopping scheduler due to unexpected error and GENERATOR_STOP_ON_UNEXPECTED_ERROR=true");
            stopScheduler();
        }
    }

    public JourneyExecutionRecord executeSingleJourney(
            String requestedRunId,
            String requestedJourneyId,
            String requestedScenario,
            Boolean requestedEnabled
    ) {
        inFlightLock.lock();
        try {
            if (!activeInFlight.compareAndSet(false, true)) {
                throw new IllegalStateException("Concurrent journey in flight violation (max_in_flight=1)");
            }

            String runId = (requestedRunId != null && !requestedRunId.isBlank()) ? requestedRunId : defaultRunId;
            String journeyId = (requestedJourneyId != null && !requestedJourneyId.isBlank())
                    ? requestedJourneyId
                    : UUID.randomUUID().toString();
            String scenarioStr = (requestedScenario != null && !requestedScenario.isBlank())
                    ? requestedScenario
                    : properties.getScenarioName();
            boolean enabled = requestedEnabled != null ? requestedEnabled : properties.isScenarioEnabled();

            ScenarioContext ctx = new ScenarioContext(runId, journeyId, ScenarioName.fromValue(scenarioStr), enabled);
            long startedAtMs = System.currentTimeMillis();
            long prevCompleted = lastCompletedAtMs.get();
            long delaySincePrevMs = prevCompleted > 0 ? Math.max(0L, startedAtMs - prevCompleted) : 0L;
            long startNanos = System.nanoTime();

            TelemetryHelper.bindContext(ctx, JourneyState.STARTED);
            TelemetryHelper.incrementCounter(
                    "commerce.journeys.started",
                    "{journey}",
                    "Total business journeys started by journey-generator",
                    Map.of(
                            "commerce.scenario", ctx.scenarioName().name(),
                            "commerce.scenario_enabled", Boolean.toString(ctx.scenarioEnabled())
                    )
            );
            log.info("Journey started: run_id={} journey_id={} scenario={} enabled={} state={}",
                    ctx.runId(), ctx.journeyId(), ctx.scenarioName(), ctx.scenarioEnabled(), JourneyState.STARTED);

            JourneyStartRequest payload = new JourneyStartRequest(ctx.runId(), ctx.journeyId(), "SKU-SYNTHETIC-001", 1);

            int httpStatus = 500;
            String state = JourneyState.INTERNAL_FAILURE.name();
            String errorCode = "";
            String cartId = "";
            String orderId = "";
            String traceId = "";

            try {
                JourneyResponse response = restClient.post()
                        .uri("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationHeaders.RUN_ID, ctx.runId())
                        .header(CorrelationHeaders.JOURNEY_ID, ctx.journeyId())
                        .header(CorrelationHeaders.SIMULATION_SCENARIO, ctx.scenarioName().name())
                        .header(CorrelationHeaders.SIMULATION_ENABLED, Boolean.toString(ctx.scenarioEnabled()))
                        .body(payload)
                        .retrieve()
                        .body(JourneyResponse.class);

                httpStatus = 202;
                if (response != null) {
                    cartId = response.cartId() != null ? response.cartId() : "";
                    orderId = response.orderId() != null ? response.orderId() : "";
                    state = response.state() != null ? response.state() : JourneyState.ORDER_PUBLISHED.name();
                    traceId = response.traceId() != null ? response.traceId() : "";
                } else {
                    state = JourneyState.ORDER_PUBLISHED.name();
                }
                if (traceId.isBlank()) {
                    traceId = TelemetryHelper.currentTraceId();
                }

                TelemetryHelper.bindEntityIds(cartId, orderId, null);
                TelemetryHelper.updateState(JourneyState.ORDER_PUBLISHED);
                TelemetryHelper.incrementCounter(
                        "commerce.journeys.completed",
                        "{journey}",
                        "Total synchronous journey calls completed successfully",
                        Map.of(
                                "commerce.scenario", ctx.effectiveScenario().name(),
                                "commerce.journey.state", state,
                                "http.response.status_code", Integer.toString(httpStatus)
                        )
                );
                log.info("Journey synchronous request succeeded: status={} cart_id={} order_id={} trace_id={} state={}",
                        httpStatus, cartId, orderId, traceId, state);
            } catch (RestClientResponseException httpEx) {
                httpStatus = httpEx.getStatusCode().value();
                try {
                    ApiErrorResponse errBody = objectMapper.readValue(httpEx.getResponseBodyAsString(), ApiErrorResponse.class);
                    errorCode = errBody.errorCode() != null ? errBody.errorCode() : "HTTP_" + httpStatus;
                    traceId = errBody.traceId() != null ? errBody.traceId() : "";
                } catch (Exception ignored) {
                    errorCode = "HTTP_" + httpStatus;
                }
                if (traceId.isBlank()) {
                    traceId = TelemetryHelper.currentTraceId();
                }
                JourneyState mappedFailure = mapErrorCode(errorCode);
                state = mappedFailure.name();
                TelemetryHelper.recordError(mappedFailure, errorCode, httpEx);
                TelemetryHelper.incrementCounter(
                        "commerce.journeys.failed",
                        "{journey}",
                        "Total synchronous journey calls returning error",
                        Map.of(
                                "commerce.scenario", ctx.effectiveScenario().name(),
                                "commerce.journey.state", state,
                                "error.code", errorCode
                        )
                );
                log.warn("Journey synchronous request returned error response: status={} error_code={} trace_id={} state={}",
                        httpStatus, errorCode, traceId, state);
            } catch (Exception unexpectedEx) {
                httpStatus = 500;
                errorCode = "CLIENT_ERROR";
                state = JourneyState.INTERNAL_FAILURE.name();
                traceId = TelemetryHelper.currentTraceId();
                TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, errorCode, unexpectedEx);
                TelemetryHelper.incrementCounter(
                        "commerce.journeys.failed",
                        "{journey}",
                        "Total synchronous journey calls returning error",
                        Map.of(
                                "commerce.scenario", ctx.effectiveScenario().name(),
                                "commerce.journey.state", state,
                                "error.code", errorCode
                        )
                );
                log.error("Journey request failed with unexpected client exception", unexpectedEx);
            } finally {
                long completedAtMs = System.currentTimeMillis();
                lastCompletedAtMs.set(completedAtMs);
                executedCount.incrementAndGet();
                double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;

                TelemetryHelper.recordHistogram(
                        "commerce.journey.client.duration",
                        "ms",
                        "End-to-end synchronous client request duration in ms",
                        durationMs,
                        Map.of(
                                "commerce.scenario", ctx.effectiveScenario().name(),
                                "commerce.journey.state", state
                        )
                );
                TelemetryHelper.clearMdc();
                activeInFlight.set(false);
            }

            long completedAtMs = lastCompletedAtMs.get();
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            JourneyExecutionRecord record = new JourneyExecutionRecord(
                    ctx.runId(),
                    ctx.journeyId(),
                    traceId,
                    cartId,
                    orderId,
                    ctx.scenarioName().name(),
                    ctx.scenarioEnabled(),
                    ctx.effectiveScenario().name(),
                    httpStatus,
                    state,
                    errorCode,
                    startedAtMs,
                    completedAtMs,
                    durationMs,
                    delaySincePrevMs
            );
            history.add(record);
            if (history.size() > 500) {
                history.remove(0);
            }
            return record;
        } finally {
            inFlightLock.unlock();
        }
    }

    public List<JourneyExecutionRecord> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public boolean isInFlight() {
        return activeInFlight.get();
    }

    private static JourneyState mapErrorCode(String errorCode) {
        if (errorCode == null) {
            return JourneyState.INTERNAL_FAILURE;
        }
        return switch (errorCode) {
            case "BE_ERROR" -> JourneyState.CHECKOUT_FAILED;
            case "DB_TIMEOUT" -> JourneyState.DB_TIMEOUT;
            case "MERCHANT_TIMEOUT" -> JourneyState.MERCHANT_TIMEOUT;
            case "BROKER_UNAVAILABLE" -> JourneyState.PAYMENT_AUTHORIZED_NOT_PUBLISHED;
            default -> JourneyState.INTERNAL_FAILURE;
        };
    }
}

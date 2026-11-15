package com.commerce.synthetic.scenario;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central OpenTelemetry helper for Span enrichment, Custom Business Metrics, and SLF4J/OTLP Log correlation.
 */
public final class TelemetryHelper {

    public static final String INSTRUMENTATION_SCOPE = "commerce.synthetic.metrics";

    public static final AttributeKey<String> ATTR_RUN_ID = AttributeKey.stringKey("commerce.run_id");
    public static final AttributeKey<String> ATTR_JOURNEY_ID = AttributeKey.stringKey("commerce.journey_id");
    public static final AttributeKey<String> ATTR_SCENARIO = AttributeKey.stringKey("commerce.scenario");
    public static final AttributeKey<Boolean> ATTR_SCENARIO_ENABLED = AttributeKey.booleanKey("commerce.scenario_enabled");
    public static final AttributeKey<String> ATTR_EFFECTIVE_SCENARIO = AttributeKey.stringKey("commerce.effective_scenario");
    public static final AttributeKey<String> ATTR_JOURNEY_STATE = AttributeKey.stringKey("commerce.journey.state");
    public static final AttributeKey<String> ATTR_CART_ID = AttributeKey.stringKey("commerce.cart_id");
    public static final AttributeKey<String> ATTR_ORDER_ID = AttributeKey.stringKey("commerce.order_id");
    public static final AttributeKey<String> ATTR_FULFILLMENT_ID = AttributeKey.stringKey("commerce.fulfillment_id");
    public static final AttributeKey<String> ATTR_ERROR_CODE = AttributeKey.stringKey("commerce.error_code");

    private static final Map<String, LongCounter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, DoubleHistogram> HISTOGRAMS = new ConcurrentHashMap<>();

    private TelemetryHelper() {
    }

    public static void bindContext(ScenarioContext context, JourneyState state) {
        if (context == null) {
            return;
        }
        Span span = Span.current();
        span.setAttribute(ATTR_RUN_ID, context.runId());
        span.setAttribute(ATTR_JOURNEY_ID, context.journeyId());
        span.setAttribute(ATTR_SCENARIO, context.scenarioName().name());
        span.setAttribute(ATTR_SCENARIO_ENABLED, context.scenarioEnabled());
        span.setAttribute(ATTR_EFFECTIVE_SCENARIO, context.effectiveScenario().name());
        if (state != null) {
            span.setAttribute(ATTR_JOURNEY_STATE, state.name());
        }

        MDC.put("run_id", context.runId());
        MDC.put("journey_id", context.journeyId());
        MDC.put("scenario", context.scenarioName().name());
        MDC.put("scenario_enabled", Boolean.toString(context.scenarioEnabled()));
        MDC.put("effective_scenario", context.effectiveScenario().name());
        if (state != null) {
            MDC.put("journey_state", state.name());
        }
        SpanContext spanContext = span.getSpanContext();
        if (spanContext.isValid()) {
            MDC.put("trace_id", spanContext.getTraceId());
            MDC.put("span_id", spanContext.getSpanId());
        }
    }

    public static void updateState(JourneyState state) {
        if (state == null) {
            return;
        }
        Span.current().setAttribute(ATTR_JOURNEY_STATE, state.name());
        MDC.put("journey_state", state.name());
    }

    public static void bindEntityIds(String cartId, String orderId, String fulfillmentId) {
        Span span = Span.current();
        if (cartId != null && !cartId.isBlank()) {
            span.setAttribute(ATTR_CART_ID, cartId);
            MDC.put("cart_id", cartId);
        }
        if (orderId != null && !orderId.isBlank()) {
            span.setAttribute(ATTR_ORDER_ID, orderId);
            MDC.put("order_id", orderId);
        }
        if (fulfillmentId != null && !fulfillmentId.isBlank()) {
            span.setAttribute(ATTR_FULFILLMENT_ID, fulfillmentId);
            MDC.put("fulfillment_id", fulfillmentId);
        }
    }

    public static void recordError(JourneyState failureState, String errorCode, Throwable exception) {
        Span span = Span.current();
        if (failureState != null) {
            span.setAttribute(ATTR_JOURNEY_STATE, failureState.name());
            MDC.put("journey_state", failureState.name());
        }
        if (errorCode != null && !errorCode.isBlank()) {
            span.setAttribute(ATTR_ERROR_CODE, errorCode);
            MDC.put("error_code", errorCode);
        }
        if (exception != null) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, exception.getMessage() != null ? exception.getMessage() : errorCode);
        } else {
            span.setStatus(StatusCode.ERROR, errorCode != null ? errorCode : "ERROR");
        }
    }

    public static String currentTraceId() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            return spanContext.getTraceId();
        }
        String mdcTrace = MDC.get("trace_id");
        return mdcTrace != null ? mdcTrace : "";
    }

    public static void clearMdc() {
        MDC.remove("run_id");
        MDC.remove("journey_id");
        MDC.remove("scenario");
        MDC.remove("scenario_enabled");
        MDC.remove("effective_scenario");
        MDC.remove("journey_state");
        MDC.remove("cart_id");
        MDC.remove("order_id");
        MDC.remove("fulfillment_id");
        MDC.remove("error_code");
    }

    public static void incrementCounter(String name, String unit, String description, Map<String, String> tags) {
        Meter meter = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_SCOPE);
        LongCounter counter = COUNTERS.computeIfAbsent(name, key ->
                meter.counterBuilder(key)
                        .setUnit(unit != null ? unit : "{count}")
                        .setDescription(description != null ? description : key)
                        .build()
        );
        counter.add(1L, toAttributes(tags));
    }

    public static void recordHistogram(String name, String unit, String description, double value, Map<String, String> tags) {
        Meter meter = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_SCOPE);
        DoubleHistogram histogram = HISTOGRAMS.computeIfAbsent(name, key ->
                meter.histogramBuilder(key)
                        .setUnit(unit != null ? unit : "ms")
                        .setDescription(description != null ? description : key)
                        .build()
        );
        histogram.record(value, toAttributes(tags));
    }

    private static Attributes toAttributes(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Attributes.empty();
        }
        AttributesBuilder builder = Attributes.builder();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                builder.put(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }
}

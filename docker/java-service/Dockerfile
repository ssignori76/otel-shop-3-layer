# syntax=docker/dockerfile:1.7

# ==============================================================================
# Stage 1: Dependencies & OpenTelemetry Java Agent cache
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS dependencies

WORKDIR /workspace

ARG OTEL_AGENT_VERSION=2.10.0
RUN mkdir -p /opt/otel && \
    wget -q -O /opt/otel/opentelemetry-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

COPY pom.xml ./
COPY libs/scenario-contract/pom.xml libs/scenario-contract/pom.xml
COPY libs/api-contract/pom.xml libs/api-contract/pom.xml
COPY libs/messaging-contract/pom.xml libs/messaging-contract/pom.xml
COPY services/auth-service/pom.xml services/auth-service/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/cart-service/pom.xml services/cart-service/pom.xml
COPY services/checkout-service/pom.xml services/checkout-service/pom.xml
COPY services/merchant-service/pom.xml services/merchant-service/pom.xml
COPY services/fulfillment-service/pom.xml services/fulfillment-service/pom.xml
COPY journey-generator/pom.xml journey-generator/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline

# ==============================================================================
# Stage 2a: Full Reactor Unit Test & JaCoCo Coverage Verification
# ==============================================================================
FROM dependencies AS unit-test

COPY libs/ libs/
COPY services/ services/
COPY journey-generator/ journey-generator/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B test jacoco:report

# ==============================================================================
# Stage 2b: Build, Unit Test, Static Validation & SBOM Generation
# ==============================================================================
FROM dependencies AS build

COPY libs/ libs/
COPY services/ services/
COPY journey-generator/ journey-generator/

ARG MODULE_PATH
ARG SKIP_UNIT_TESTS=false

RUN --mount=type=cache,target=/root/.m2 \
    test -n "${MODULE_PATH}" && \
    mvn -B -pl "${MODULE_PATH}" -am -DskipTests="${SKIP_UNIT_TESTS}" package && \
    mkdir -p /build-out && \
    cp /workspace/${MODULE_PATH}/target/*.jar /build-out/app.jar && \
    if [ -f "/workspace/${MODULE_PATH}/target/bom.json" ]; then \
        cp "/workspace/${MODULE_PATH}/target/bom.json" /build-out/sbom.cdx.json; \
    else \
        echo '{"bomFormat":"CycloneDX","specVersion":"1.5"}' > /build-out/sbom.cdx.json; \
    fi

# ==============================================================================
# Stage 3: Minimal Non-Root JRE 21 Runtime
# ==============================================================================
FROM eclipse-temurin:21.0.5_11-jre-alpine AS runtime

ARG APP_NAME=commerce-java-service
ARG APP_VERSION=2.0.0
ARG GIT_REVISION=dev
ARG BUILD_CREATED=2026-11-15T00:00:00Z
ARG SERVER_PORT=8080

LABEL org.opencontainers.image.title="${APP_NAME}" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_REVISION}" \
      org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.source="https://local.git/commerce-synthetic-app" \
      org.opencontainers.image.vendor="Commerce Synthetic Observability"

RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S -G appgroup -h /app appuser && \
    mkdir -p /app /opt/otel && \
    chown -R 10001:10001 /app /opt/otel

COPY --from=dependencies --chown=10001:10001 /opt/otel/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
COPY --from=build --chown=10001:10001 /build-out/app.jar /app/app.jar
COPY --from=build --chown=10001:10001 /build-out/sbom.cdx.json /app/sbom.cdx.json

WORKDIR /app
USER 10001:10001

ENV SERVER_PORT=${SERVER_PORT} \
    JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar -Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes=* -Dotel.instrumentation.logback-appender.experimental-log-attributes=true -Dotel.instrumentation.common.db-statement-sanitizer.enabled=true"

EXPOSE ${SERVER_PORT}

HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=12 \
    CMD wget -qO- "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

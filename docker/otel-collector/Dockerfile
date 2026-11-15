FROM otel/opentelemetry-collector-contrib:0.114.0 AS otelcol

FROM alpine:3.20.3

RUN apk add --no-cache ca-certificates wget && \
    addgroup -g 10001 -S otel && \
    adduser -u 10001 -S -G otel otel && \
    mkdir -p /var/log/otel /etc/otelcol-contrib && \
    chown -R 10001:10001 /var/log/otel /etc/otelcol-contrib

COPY --from=otelcol /otelcol-contrib /usr/local/bin/otelcol-contrib

USER 10001:10001

EXPOSE 4317 4318 8889 13133

HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=10 \
    CMD wget -qO- http://127.0.0.1:13133/ || exit 1

ENTRYPOINT ["/usr/local/bin/otelcol-contrib"]
CMD ["--config=/etc/otelcol-contrib/config.yaml"]

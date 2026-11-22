FROM httpd:2.4.62-alpine

ARG APP_VERSION=2.0.0
ARG GIT_REVISION=dev
ARG BUILD_CREATED=2026-11-22T00:00:00Z

LABEL org.opencontainers.image.title="commerce-apache-web" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_REVISION}" \
      org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.source="https://local.git/commerce-synthetic-app"

RUN apk add --no-cache libcap && \
    setcap 'cap_net_bind_service=+ep' /usr/local/apache2/bin/httpd && \
    mkdir -p /usr/local/apache2/conf/conf.d /usr/local/apache2/logs /var/run/apache2 && \
    chown -R www-data:www-data /usr/local/apache2 /var/run/apache2

COPY docker/apache/httpd.conf /usr/local/apache2/conf/httpd.conf
COPY docker/apache/conf.d/reverse-proxy.conf /usr/local/apache2/conf/conf.d/reverse-proxy.conf
COPY --chown=www-data:www-data frontend/ /usr/local/apache2/htdocs/

USER www-data:www-data

EXPOSE 80

HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=10 \
    CMD wget -qO- http://127.0.0.1:80/healthz | grep -q '"status":"UP"' || exit 1

CMD ["httpd-foreground"]

FROM python:3.12.7-alpine

RUN apk add --no-cache curl jq mysql-client && \
    pip install --no-cache-dir pymysql==1.1.1 && \
    addgroup -g 10001 -S testgroup && \
    adduser -u 10001 -S -G testgroup testuser && \
    mkdir -p /app /reports && \
    chown -R 10001:10001 /app /reports

WORKDIR /app
COPY --chown=10001:10001 test/ /app/test/

USER 10001:10001

ENTRYPOINT ["python3", "/app/test/end-to-end/run_e2e_suite.py"]

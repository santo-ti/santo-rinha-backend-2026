# syntax=docker/dockerfile:1
# Native-image build (GraalVM) — path to fit the 160 MB/instance budget.

FROM --platform=linux/amd64 ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /app
RUN microdnf install -y curl || true

# Cache the reference dataset download early (independent of source changes).
ARG REFERENCES_URL=https://raw.githubusercontent.com/santo-ti/rinha-de-backend-2026/main/resources/references.json.gz
RUN curl -sSL "$REFERENCES_URL" -o /refs.json.gz

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src

# JVM fat jar (used for the index builder tool and the agent run), then the index.
RUN ./gradlew --no-daemon buildFatJar
RUN java -Xmx4g -cp "build/libs/*" dev.santo.tools.BuildIndexKt /refs.json.gz index.bin

# Capture native-image reachability metadata by exercising the app on the JVM with
# the tracing agent (covers Ktor CIO's reflective AtomicReferenceFieldUpdater fields).
RUN printf '%s' '{"id":"tx-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}' > /tmp/p.json \
 && ( INDEX_PATH=/app/index.bin java -agentlib:native-image-agent=config-output-dir=/app/native-config -cp "build/libs/*" dev.santo.MainKt & echo $! > /tmp/app.pid ) \
 && for i in $(seq 1 40); do curl -sf -o /dev/null http://localhost:8080/ready && break || sleep 1; done \
 && curl -s -X POST http://localhost:8080/fraud-score -H "Content-Type: application/json" -d @/tmp/p.json > /dev/null || true \
 && sleep 1 \
 && kill -TERM "$(cat /tmp/app.pid)" || true \
 && sleep 3 \
 && echo "--- generated native-config ---" && ls -la /app/native-config

# Supplement the agent metadata with a hand-written reflect-config (kept in a late
# layer so iterating on it only re-runs nativeCompile, not the index/agent steps).
COPY native-config/manual-reflect-config.json /app/native-config/manual-reflect-config.json

# Native server binary. The GraalVM plugin tasks are not configuration-cache
# compatible, so disable it for this invocation.
RUN ./gradlew --no-daemon --no-configuration-cache nativeCompile

FROM --platform=linux/amd64 oraclelinux:9-slim AS runtime
WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/rinha-server /app/rinha-server
COPY --from=builder /app/index.bin /app/index.bin
ENV INDEX_PATH=/app/index.bin
EXPOSE 8080
ENTRYPOINT ["/app/rinha-server"]

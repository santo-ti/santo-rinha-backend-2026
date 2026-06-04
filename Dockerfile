# syntax=docker/dockerfile:1
# Native-image build (GraalVM) — path to fit the 160 MB/instance budget.

FROM --platform=linux/amd64 ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /app
RUN microdnf install -y curl || true

# Cache the reference dataset download early (independent of source changes).
# Defaults to the official challenge dataset; override at build time if needed.
ARG REFERENCES_URL=https://raw.githubusercontent.com/zanfranceschi/rinha-de-backend-2026/main/resources/references.json.gz
RUN curl -sSL "$REFERENCES_URL" -o /refs.json.gz

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src

# JVM fat jar (used for the index builder tool and the agent run), then the index.
RUN ./gradlew --no-daemon buildFatJar
# Build the IVF index over the FULL 3M reference set with EXACT bbox branch-and-bound
# (search.IvfIndex): k=4096 coarse cells, any cell > IVF_MAX_CELL=256 split for tighter
# boxes, k1=128 districts. The search visits only the cells whose AABB could still hold a
# closer neighbor than the current 5th-NN → equals a full brute-force top-5 (zero routing
# miss) while touching only a few cells/query (offline over 3M: E=0, p99 ~16.6k points →
# 18.5ms, 0 errors at #7882/#7978). NPROBE is ignored by the exact search (format compat).
# (The KD-tree builder/search remain in the repo — BuildKdTreeKt — as an offline spike; the
# best-first BBF saturated under load at 14 dims, see .docs/santannaf-analysis.md.)
ARG INDEX_MAX_SIZE=3000000
# -Xmx5g: parsing the 3M refs peaks ~1.5GB; IVF_PARALLELISM unset => k-means uses all cores.
RUN IVF_META_CELLS=128 IVF_MAX_CELL=256 java -Xmx5g --add-modules jdk.incubator.vector -cp "build/libs/*" dev.santo.tools.BuildIndexKt /refs.json.gz index.bin $INDEX_MAX_SIZE

# Capture native-image reachability metadata by exercising the app on the JVM with
# the tracing agent (covers Ktor CIO's reflective AtomicReferenceFieldUpdater fields).
RUN printf '%s' '{"id":"tx-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}' > /tmp/p.json \
 && ( INDEX_PATH=/app/index.bin java -agentlib:native-image-agent=config-output-dir=/app/native-config --add-modules jdk.incubator.vector -cp "build/libs/*" dev.santo.bootstrap.MainKt & echo $! > /tmp/app.pid ) \
 && for i in $(seq 1 40); do curl -sf -o /dev/null http://localhost:8080/ready && break || sleep 1; done \
 && curl -s -X POST http://localhost:8080/fraud-score -H "Content-Type: application/json" -d @/tmp/p.json > /dev/null || true \
 && sleep 1 \
 && kill -TERM "$(cat /tmp/app.pid)" || true \
 && sleep 3 \
 && echo "--- generated native-config ---" && ls -la /app/native-config

# Second agent pass over a UNIX socket: captures Ktor CIO's unixConnector reachability (the
# UDS path the TCP pass above misses — a likely contributor to v1.6.0's "No status"). Merged
# into the same config so the native binary works on TCP or UDS.
RUN ( SERVER_SOCKET_PATH=/tmp/agent.sock INDEX_PATH=/app/index.bin java -agentlib:native-image-agent=config-merge-dir=/app/native-config --add-modules jdk.incubator.vector -cp "build/libs/*" dev.santo.bootstrap.MainKt & echo $! > /tmp/app3.pid ) \
 && for i in $(seq 1 40); do [ -S /tmp/agent.sock ] && curl -sf --unix-socket /tmp/agent.sock http://localhost/ready && break || sleep 1; done \
 && curl -s --unix-socket /tmp/agent.sock -X POST http://localhost/fraud-score -H "Content-Type: application/json" -d @/tmp/p.json > /dev/null || true \
 && sleep 1 \
 && kill -TERM "$(cat /tmp/app3.pid)" || true \
 && sleep 2 \
 && echo "--- merged UDS native-config ---"

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
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
ENV INDEX_PATH=/app/index.bin
# IVF exact search needs no recall knob. IVF_POINT_CAP (search.IvfIndex) is an optional
# runtime work-cap (unset = fully exact, 0 errors) — sweep it on the submission branch to
# trade the saturated tail for p99 without a rebuild; leave unset to keep the 0-error path.
# SIMD cell scan on (proven 4766 at #8204, bit-exact); the UDS path is detection-safe too.
ENV IVF_SIMD_SCAN=1
EXPOSE 8080
# entrypoint binds TCP (no SERVER_SOCKET_PATH) or a chmod-0666 Unix socket (UDS topology).
ENTRYPOINT ["/app/entrypoint.sh"]

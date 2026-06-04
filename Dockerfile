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

# Second agent pass on the NIO reactor bound to a UNIX DOMAIN SOCKET (the submission
# transport): captures the Selector / SocketChannel AND the UnixDomainSocketAddress /
# StandardProtocolFamily.UNIX reachability, merged in so the native binary can bind and
# serve over UDS at runtime (the path the Ktor attempt lacked metadata for). Exercised
# via curl's --unix-socket so /ready and /fraud-score are answered over the socket.
RUN ( SERVER_ENGINE=reactor SERVER_SOCKET_PATH=/tmp/api.sock INDEX_PATH=/app/index.bin java -agentlib:native-image-agent=config-merge-dir=/app/native-config --add-modules jdk.incubator.vector -cp "build/libs/*" dev.santo.bootstrap.MainKt & echo $! > /tmp/app2.pid ) \
 && for i in $(seq 1 40); do curl -sf -o /dev/null --unix-socket /tmp/api.sock http://localhost/ready && break || sleep 1; done \
 && curl -s -X POST --unix-socket /tmp/api.sock http://localhost/fraud-score -H "Content-Type: application/json" -d @/tmp/p.json > /dev/null || true \
 && sleep 1 \
 && kill -TERM "$(cat /tmp/app2.pid)" || true \
 && sleep 2 \
 && echo "--- merged reactor UDS native-config ---"

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
# Image defaults to the proven-best config: Ktor CIO + scalar exact IVF search over TCP
# (official previews: ~17ms / score ~4766, 0 error). The single-thread NIO reactor and the
# SIMD cell scan are kept in the image but OPT-IN via env, since both regressed p99 under the
# 0.45-CPU/900-rps saturation (reactor+SIMD 88ms; reactor+scalar+UDS 46ms vs Ktor+scalar 17ms):
#   - SERVER_ENGINE=reactor  -> single-thread NIO reactor (else Ktor CIO).
#   - IVF_SIMD_SCAN=1         -> full-14-dim SIMD block scan (else scalar per-dim early-exit).
#   - IVF_POINT_CAP=<n>       -> optional work-cap trading exactness for p99 (unset = 0-error).
ENV IVF_SIMD_SCAN=0
EXPOSE 8080
ENTRYPOINT ["/app/rinha-server"]

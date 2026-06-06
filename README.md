# santo-rinha-backend-2026

Fraud-detection backend for the **[Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026)** challenge.

Given a transaction, the service answers whether it should be **approved** and its **fraud score**, by finding the 5 nearest neighbors of the transaction's feature vector in a ~3-million-row reference set and applying a k-NN decision rule — all under a hard budget of **1 CPU and 350 MB across the entire stack**, at a ramping load up to ~900 req/s.

## Result

| Metric | Value |
|---|---|
| p99 latency | ~17–20 ms |
| HTTP errors | 0 |
| Detection errors | 0 (the search is exact) |
| Resource budget | 1.0 CPU / 350 MB total |

## Stack

- **Kotlin 2 / JVM 25** + **Ktor 3.4 (CIO engine)** — coroutine-based HTTP; no blocking work on the request path.
- **GraalVM native-image** — a plain JVM (baseline RSS + the 3M-vector index) OOMs at 160 MB/instance, so a native image is required to fit the budget. No Logback (SLF4J falls back to NOP); routes use compile-time serializers (no `ContentNegotiation`) to keep the hot path lean and native-friendly.
- **HAProxy** (TCP mode) load balancer in front of **2 API instances**.

## How it works

Every `POST /fraud-score` request runs an allocation-light, inline pipeline:

1. **Vectorize** the raw request bytes into a 14-dimension feature vector — a byte-by-byte scan, no DTO / String / JSON-object graph on the hot path.
2. **Search** the 5 nearest neighbors with an **exact int16 IVF** (inverted-file) index: reference vectors are quantized to `int16` and partitioned into cells by k-means; a per-cell bounding-box **branch-and-bound** visits only the cells that could still hold a closer neighbor than the current 5th-NN. The bound is admissible, so the answer equals a full brute-force top-5 — **zero routing error** — while touching only a handful of cells per query (~65 µs/search).
3. **Decide**: count the fraud labels among the 5 neighbors and apply the policy. On *any* error the handler returns a safe fallback — the API **never responds 5xx** (an HTTP error is the most expensive failure under the contest's scoring).

The binary index (`index.bin`) is built **once at image-build time** from the reference dataset and memory-loaded at startup; the runtime never parses the reference JSON.

## Architecture

The app is composed of `Application` extension functions aggregated by a single root module. Packages are organized by layer/intent (`dev.santo.*`):

```
bootstrap/      main + dependency wiring + async index load (readiness gated on it)
api/            GET /ready  +  POST /fraud-score  (explicit serializers, never 5xx)
dto/            neutral request/response DTOs, shared by the HTTP edge and the domain
fraud/          decision flow: vectorize -> k-NN search -> policy (FALLBACK on any error)
vectorization/  14-dim byte-scan vectorizer + normalization / MCC-risk tables
search/         int16 quantization, IVF index, exact bbox branch-and-bound k-NN
tools/          offline build tooling (reference dataset -> index.bin)
```

Dependencies point inward (or to the neutral `dto`); the domain never imports the API layer. The runtime, the offline build tooling, and test-only oracles are kept physically separate.

## Running

```bash
# Local dev on the JVM (fast iteration), binds 0.0.0.0:8080
./gradlew run

# Tests
./gradlew test

# Full local stack (HAProxy + 2 API instances) on port 9999
docker compose up --build

# GraalVM native image — the submission build (needs Docker; heavy, several minutes)
docker build -t rinha-api .
```

JVM heap is pre-tuned in `gradle.properties`. The native build downloads the reference dataset, builds `index.bin`, captures native-image reachability metadata via the tracing agent, and compiles the binary into a slim runtime image.

## Endpoints

| Endpoint | Description |
|---|---|
| `GET /ready` | Readiness probe — 200 once the index is loaded, 503 otherwise. |
| `POST /fraud-score` | Body: the transaction. Response: `{ "approved": boolean, "fraud_score": number }`. |

## Deployment

The submission runs as **2 identical native-image instances behind HAProxy** on port **9999** (round-robin, no business logic in the balancer). The image is published to GHCR under an immutable, versioned tag; the orphan `submission` branch carries only the deploy files (`docker-compose.yml` + `haproxy.cfg` + `info.json`). Resource split: API **0.45 CPU / 160 MB** ×2 + LB **0.10 CPU / 30 MB** = 1.0 CPU / 350 MB.

## License

[MIT](LICENSE).

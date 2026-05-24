# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

Submission for **Rinha de Backend 2026** — a high-throughput backend challenge. Performance, resource limits (CPU/memory) and latency under load drive most design decisions. When in doubt, prefer the option that scales better under contention over the one that is more idiomatic.

Stack: Kotlin 2 + JVM 25, Ktor 3.4.0 (CIO engine), kotlinx.serialization, Gradle Kotlin DSL. The **submission image is a GraalVM native-image** — a plain JVM (baseline RSS + 3M-vector index) OOMs under load at 160 MB/instance, so native is required to fit the budget. No Logback (SLF4J falls back to NOP); routes use explicit compile-time serializers (no ContentNegotiation) to keep the hot path lean and native-friendly.

## Commands

All commands use the Gradle wrapper. On Windows use `gradlew.bat`; on Bash use `./gradlew`.

```powershell
# Run the server (binds 0.0.0.0:8080)
./gradlew run

# Build (no tests)
./gradlew assemble

# Build + run tests
./gradlew build

# Tests
./gradlew test
./gradlew test --tests "dev.santo.ServerTest"
./gradlew test --tests "dev.santo.VectorizerTest"

# Continuous test loop
./gradlew test -t

# Fat / runnable distribution (Ktor plugin tasks)
./gradlew buildFatJar
./gradlew runFatJar

# GraalVM native image (needs a GraalVM JDK; the submission builds this inside Docker)
./gradlew nativeCompile --no-configuration-cache   # GraalVM plugin tasks aren't cc-compatible
```

JVM heap is pre-tuned in `gradle.properties` (Gradle: `-Xmx12g`, Kotlin daemon: `-Xmx8g`). Configuration cache and build cache are enabled — if you see "configuration cache problem" errors, fix the build script rather than disabling the cache.

### Docker / Submission

The submission topology (Nginx load balancer + 2 API instances) lives in `docker-compose.yml`. The load balancer publishes the public port **9999**; the API instances listen on `8080` internally and are not exposed to the host.

```powershell
# Build images and start the full stack (lb + api-1 + api-2)
docker compose up --build

# Stop and remove the stack
docker compose down

# Follow the load balancer logs
docker compose logs -f lb
```

The image is built from the multi-stage `Dockerfile`: a **GraalVM native-image** builder (downloads the reference dataset → builds the `index.bin` artifact → captures native-image reachability metadata via the tracing agent → `nativeCompile`) into a slim `oraclelinux:9-slim` runtime. The native build is heavy (GraalVM image + several minutes + ~6 GB RAM for `native-image`). For the iterative dev loop use `./gradlew run` (plain JVM); the native build is only for the submission. The `native-config/manual-reflect-config.json` supplements the agent metadata for Ktor CIO's reflective field updaters.

## Architecture

The application is composed of **module extension functions on `Application`**, aggregated by a single root module. This is the pattern to follow when adding new features (auth, DB, metrics, etc.) — create `configureX()` and call it from `rootModule`.

Packages are organized by layer/intent, with the fraud domain in the foreground (`dev.santo.*`):

```
bootstrap/   → main.kt: embeddedServer(CIO, 8080) { rootModule(components) } + IndexLoader.loadAsync;
               Application.kt: rootModule() = configureRouting() + configureFraudScore(); AppComponents (DI wiring)
api/         → FraudScoreRoutes.kt: GET /ready (gated on index loaded) + POST /fraud-score
               (explicit serializers, never 5xx)
dto/         → neutral request/response DTOs (FraudScoreRequest.kt + sub-DTOs, FraudScoreResponse.kt);
               shared by the HTTP edge and the domain — belongs to no layer
fraud/       → domain: FraudDetectorService (vectorize → k-NN search → decision; FALLBACK on any error)
               + FraudPolicy (k=5, threshold 0.6, fraudScore/isApproved, VectorIndex.scoreOf bridge)
vectorization/ → Vectorizer (14 dims) + normalization.json / mcc_risk.json loaders
search/      → k-NN engine: quantization (int8), Distance, bucketing + VP-Tree search,
               IndexReader (binary load), IndexState, VectorIndex / LabeledVector
tools/       → offline build-tooling: References (JSON parser), IndexBuilder, IndexWriter,
               BuildIndex.kt (references.json.gz → index.bin at image build, loaded at startup)
```

Three execution zones are kept physically separate: production runtime (`bootstrap`/`api`/`dto`/`fraud`/`vectorization`/`search`), offline build-tooling (`tools`), and test-only oracles (`BruteForceIndex`/`QuantizedBruteForceIndex` live in `src/test`). The runtime loads the binary `index.bin` and never parses the reference JSON — that parser (`References`) is in `tools`.

Dependencies point inward (or to the neutral `dto`), never outward: the domain packages (`fraud`, `vectorization`) MUST NOT import the API layer (`dev.santo.api`). Shared request/response types live in the neutral `dev.santo.dto`. The only production code that imports `dev.santo.api` is the composition root `bootstrap` (`rootModule` wires `configureRouting`/`configureFraudScore`), which legitimately assembles every layer.

The fraud-detection design (vectorization, exact bucketed VP-Tree search, int8 quantization, native-image, why a plain JVM fails the memory budget) is documented in `openspec/changes/add-fraud-score-endpoint/design.md`.

Tests use `io.ktor.server.testing.testApplication` and bootstrap via `application { rootModule() }`. Always test against `rootModule` (not individual `configureX()` functions) so the full plugin pipeline is exercised.

Engine is **CIO** (coroutine-based, no Netty). Do not introduce blocking calls inside route handlers — wrap unavoidable blocking work in `withContext(Dispatchers.IO)`.

For deployment, the app runs as 2 identical instances behind an Nginx load balancer on port **9999** (round-robin, no business logic in the balancer). See `docker-compose.yml` and `nginx/nginx.conf`. `GET /ready` is the readiness probe consumed by the load balancer and the contest orchestrator.

## OpenSpec Workflow

This repo uses [OpenSpec](https://github.com/anthropics/openspec) for spec-driven changes. Layout:

- `openspec/changes/` — active change proposals (each = its own folder)
- `openspec/changes/archive/` — applied changes
- `openspec/specs/` — current capability specs (source of truth)
- `openspec/config.yaml` — project config; **artifacts must be written in pt-BR**, but technical terms (`API`, `REST`, etc.), code, and file paths stay in English

Use the `/openspec-*` slash commands (propose, apply, archive, explore) rather than editing the openspec tree by hand.

## Conventions

- JVM toolchain: **25** (set via `kotlin { jvmToolchain(25) }`; auto-provisioned by the `foojay-resolver-convention` plugin).
- Package root: `dev.santo` (matches `group` in `build.gradle.kts`)
- Source code, comments, logs: **English**. User-facing artifacts in `openspec/` (proposals, specs): **Brazilian Portuguese**.
- Version catalogs: `libs.*` (Kotlin/plugins) and `ktorLibs.*` (Ktor BOM 3.4.0). Add new Ktor deps via `ktorLibs` to keep versions aligned.

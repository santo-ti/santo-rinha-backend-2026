# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

Submission for **Rinha de Backend 2026** — a high-throughput backend challenge. Performance, resource limits (CPU/memory) and latency under load drive most design decisions. When in doubt, prefer the option that scales better under contention over the one that is more idiomatic.

Stack: Kotlin 2 + JVM 25, Ktor 3.4.0 (CIO engine), kotlinx.serialization, Logback, Gradle Kotlin DSL.

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
./gradlew test --tests "dev.santo.ServerTest.test root endpoint"

# Continuous test loop
./gradlew test -t

# Fat / runnable distribution (Ktor plugin tasks)
./gradlew buildFatJar
./gradlew runFatJar
./gradlew buildImage         # Docker image via Ktor plugin
./gradlew publishImageToLocalRegistry
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

The image is built from the multi-stage `Dockerfile` (Temurin JDK 25 builder → JRE 25 runtime). For the iterative dev loop, keep using `./gradlew run` — Docker is only needed to validate the submission topology and resource limits.

## Architecture

The application is composed of **module extension functions on `Application`**, aggregated by a single root module. This is the pattern to follow when adding new features (auth, DB, metrics, etc.) — create `configureX()` and call it from `rootModule`.

```
main.kt                 → embeddedServer(CIO, 8080) { Application::rootModule }
Application.kt          → rootModule() = configureSerialization() + configureRouting()
Serialization.kt        → installs ContentNegotiation { json() }
Routing.kt              → routing { ... } — all HTTP endpoints live here today
```

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

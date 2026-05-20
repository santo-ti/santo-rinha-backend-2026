# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

Submission for **Rinha de Backend 2026** — a high-throughput backend challenge. Performance, resource limits (CPU/memory) and latency under load drive most design decisions. When in doubt, prefer the option that scales better under contention over the one that is more idiomatic.

Stack: Kotlin 2 + JVM 21, Ktor 3.4.0 (CIO engine), kotlinx.serialization, Logback, Gradle Kotlin DSL.

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

## OpenSpec Workflow

This repo uses [OpenSpec](https://github.com/anthropics/openspec) for spec-driven changes. Layout:

- `openspec/changes/` — active change proposals (each = its own folder)
- `openspec/changes/archive/` — applied changes
- `openspec/specs/` — current capability specs (source of truth)
- `openspec/config.yaml` — project config; **artifacts must be written in pt-BR**, but technical terms (`API`, `REST`, etc.), code, and file paths stay in English

Use the `/openspec-*` slash commands (propose, apply, archive, explore) rather than editing the openspec tree by hand.

## Conventions

- Package root: `dev.santo` (matches `group` in `build.gradle.kts`)
- Source code, comments, logs: **English**. User-facing artifacts in `openspec/` (proposals, specs): **Brazilian Portuguese**.
- Version catalogs: `libs.*` (Kotlin/plugins) and `ktorLibs.*` (Ktor BOM 3.4.0). Add new Ktor deps via `ktorLibs` to keep versions aligned.

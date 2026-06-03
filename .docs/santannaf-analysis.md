# santannaf/rinha-2026 — Technique Analysis & Port Roadmap

Reference competitor: [`santannaf/rinha-2026`](https://github.com/santannaf/rinha-2026)
(Java 25 + GraalVM native-image). Contest result: **final ~5251, p99 ~5.6ms, 0 errors.**
Goal of this doc: inventory every technique he uses, map it against our Kotlin/GraalVM
stack, and rank what to port next to reach **top-50 with Kotlin**.

> Source read at HEAD `be7b8e1`. This is a living doc — update it as we port items.

## KD-tree post-mortem (v1.11.0 — REVERTED)

v1.11.0 shipped arthurd3's best-first KD-tree BBF (`search.KdTreeIndex`) at `KD_VISIT_BUDGET=10000`.
Official #8100: **p99 1515ms, 289 http_errors, score 384** (vs IVF #7882/#7978: 18.5ms, 0 err, 4734).
Root cause: in 14 dims the per-split-dim slab bound prunes almost nothing, so (a) at budget 10k the
search is *inexact* (10 FP + 8 FN — offline E=0 was sample bias), and (b) best-first is a random-access
walk over the 84MB node array → every visit a cache miss → ~3-4× the per-query CPU of the IVF's
*sequential* cell scan → ρ≈1 at 450 RPS/0.45 CPU → saturation → 289 timeouts (E weight 5× = 1445 of
1479). No budget gives 0-error AND low p99: the two failure modes are coupled and fundamental. The IVF
exact search dominates it on both axes. **Production reverted to IVF** (Dockerfile builds `BuildIndexKt`);
the KD-tree builder/search stay in the repo as an offline spike only.

## Critical meta-findings (these correct earlier assumptions)

1. **His winning path is SCALAR, not SIMD.** The Vector API path was *reverted* — under
   native-image it slow-emulated to ~1200ms p99. The C/AVX2 FFM kernel exists only on a
   `spike/native-c-kernel` branch and is **not** in the 5251 build. **We already have
   working Vector-API SIMD in native (`-march=x86-64-v3`) — we are AHEAD of him here.**
2. **No PGO anywhere.** Pure AOT + runtime warmup. PGO is greenfield for both of us.
3. **mmap is NOT active in his winning config** (`MMAP=false`). The contiguous-int16 `.idx`
   is heap-resident (~84MB), exactly like ours. mmap/madvise/huge-pages was a *superseded*
   3030-era technique. Deprioritize.
4. **His biggest SCORE jumps were CORRECTNESS, not speed**: corrected MCC risk map
   (+1035) and exact scan-all (0% error). His biggest LATENCY wins were **UDS (−1ms)** and
   **HAProxy (−1ms)** — both transport, not compute.

## His score progression (the ranked roadmap)

| Stage | p99 | failure | score | What changed |
|---|---:|---:|---:|---|
| Undertow + TCP | 5.07ms | — | 2768 | framework baseline |
| + NIO + UDS + mmap + HAProxy | 2.81ms | 1.77% | 3030 | infra rewrite |
| + corrected MCC risk map | 2.88ms | 0.28% | **4065** | correctness (+1035) |
| + exact repair (scan-all bbox, 0% err) | 35.9ms | **0%** | 4444 | killed errors, p99 blew up |
| + contiguous layout + cell-split + int16 | **5.60ms** | **0%** | **5251** | made exact search fast |

Per-optimization latency deltas (his A/B): NIO vs Undertow +0.7ms; **UDS vs TCP −1ms**;
mmap pre-fault −0.5ms/+50; IVF early-stop −0.3ms; **HAProxy vs nginx −1ms/+130**.

## Per-area inventory

### 1. Vector index & exact search — ✅ PORTED (v1.7.0)
Two-level IVF + exact bbox branch-and-bound + cell-split. `searchFraudCount`
(`IvfVectorIndex.java:358`): seed-scan nearest super→leaf, then visit every super/leaf
whose bbox lower bound ≤ current 5th-NN (admissible → exact = brute-force top-5 → 0
routing error). Params: `COARSE_CLUSTERS=2048`, `MAX_CLUSTER_SIZE=1024`, `SUPER_CLUSTERS=128`.
**We ported this (`search/IvfIndex.kt`, `tools/IvfBuilder.splitLargeCells`).** Our split cap
is **256** (his 1024) → tighter boxes; offline over 3M: E=0, points mean 3.4k / p99 16.6k.
Details to verify in ours: (a) inner `sum>limit` early-exit in the bbox bound — ✅ have it;
(b) per-dimension early-exit *after every dim* in the cell scan — ⚠️ ours uses the SIMD
block kernel (full 14-dim), no per-dim early-exit (different tradeoff, see §2); (c) tie-break
by `(dist, origId)` ascending — ⚠️ ours ties by insertion order, verify it matches.

### 2. Distance kernel / SIMD — ✅ WE ARE AHEAD
His production scan is **scalar** with full per-dimension early-exit (`scanCluster`, 14 dims
unrolled, `if (sum>worst) continue` after each). The C kernel (`rinha_kernel.c`,
`_mm256_madd_epi16` on int16, rows at stride-14 read-past-and-mask) is a non-shipped spike.
**We have a working `ShortVector` SoA-16 kernel in native (`BlockDistance.java`) — the thing
he couldn't get working.** Do NOT port the C/FFM kernel (lateral, adds a C toolchain).
*Open question:* his per-dim early-exit can beat a full-SIMD scan when most points are far
(common on the saturated tail). A hybrid (SIMD for the seed/near cells, scalar early-exit for
the far tail) is a possible micro-opt — low priority while p99 is fine.

### 3. Custom NIO HTTP server — ❌ GAP (highest structural ceiling)
`NioEventLoop` + `HttpRequestParser`: single-thread reactor, one `Selector`, **reused scratch
buffers as instance fields** (`double[14]`, `short[14]`, Top5), zero-alloc steady state,
keep-alive + pipelining, backpressure via `OP_WRITE`. Pre-allocated `pathBuf/bodyBuf/lineBuf`,
byte-compare routing. Claimed +0.7ms vs framework. **We use Ktor CIO** (coroutine-per-conn,
per-request allocation). This is the single biggest structural latency difference and the
main source of his low tail. Portable to Kotlin as a raw `java.nio` loop (~400 lines, no deps,
native-image-clean). Anderson OK'd dropping Ktor. **Pair with §6.**

### 4. Pre-rendered responses — ✅ HAVE IT
`PreRendered`: 6 full HTTP responses (`fraud_count` 0–5) built once, indexed by the int count.
We have `api/FraudResponses.kt` (pre-serialized bodies via `respondBytes`). One refinement: he
ships the *full HTTP response* (status+headers+body) as one `byte[]`; only relevant if we drop
Ktor (§3) and write bytes to the socket directly.

### 5. Memory / mmap — ⏭️ DEPRIORITIZE
`NativeMemAdvise` (FFM `madvise` MADV_WILLNEED/HUGEPAGE) + `MappedByteBuffer.load()` pre-fault.
**But `MMAP=false` in the winning config** — the int16 `.idx` is heap-resident like ours. Can't
`madvise` a GC array anyway. Superseded technique; skip unless we move the index off-heap.

### 6. Zero-alloc vectorizer — ✅ PORTED (DTO-free byte-scan)
`SchemaAwareVectorizationStrategy`: byte-by-byte JSON→`double[14]`, no String/Map/parser.
Hand-rolled float parse, Sakamoto day-of-week + Hinnant days-from-civil for dates, byte-scan
`known_merchants` membership, `clamp01` with 4-decimal quantization to match the reference. ~4µs.
**We had kotlinx.serialization** → allocated a DTO tree per request → GC pressure → p99 tail.
**Ported as `vectorization.ByteVectorizer`** (route now reads `call.receive<ByteArray>()` → byte-scan
→ pooled per-thread scratch, no DTO/List/String-field graph). To sidestep the float-parse risk the
analysis warned about, numbers are parsed via the SAME `Double.parseDouble` on the exact numeric
substring (bit-identical doubles by construction); `ByteVectorizerTest` pins that the QUANTIZED int16
codes match the DTO path on every example payload (compact + pretty), so 0-error is preserved. Only
the `mcc` lookup key still allocates a tiny String. A fully-zero-alloc float parser + a byte-keyed mcc
table are the remaining micro-opts; they need a correctly-rounded parser to stay exact.

### 7. Build & runtime — ✅ MOSTLY HAVE; PGO is greenfield
`-march=x86-64-v3`, `--add-modules=jdk.incubator.vector`, native-image, tracing-agent metadata,
**runtime warmup** (`selfWarmup`: runs the search on the index's own rows + jitter before
`/ready` — warms page cache/branch predictors, not JIT). We have all of this. **PGO: neither of
us has it** — Oracle GraalVM `--pgo-instrument`→`--pgo` on the scan loop could be our
differentiator. Spike later (needs Oracle GraalVM; CE may lack it).

### 8. Docker / infra — ⚠️ PARTIAL
Topology: HAProxy (0.30 CPU/40MB) + 2 APIs (0.35 CPU/155MB each) = 1.0 CPU/350MB, **LB↔API over
UDS on a tmpfs volume**. `haproxy.cfg`: `nbthread 1`, `balance leastconn`, `http-reuse always`,
`option http-no-delay`, `no log`. cpuset pinning is described but only in the A/B composes, not
the default.
- **UDS (−1ms): we tried it, it failed the contest health check (`No status`, #7683).** His
  operational details that make it pass: tmpfs-backed socket dir, **0666 perms on the socket**,
  **delete stale socket on bind**, HAProxy `check inter 2s`. Revisit with these.
- **HAProxy tuning**: adopt `balance leastconn` + `http-reuse always` + `http-no-delay` (tail
  latency under keep-alive). Near-zero effort.
- **cpuset pinning**: add `cpuset` to our compose; low effort, plausible cache win.

## What we have vs. what's missing

| Technique | santannaf | us | Status |
|---|:---:|:---:|---|
| GraalVM native `-march=v3` | ✅ | ✅ | done |
| Working SIMD in native | ❌ (reverted) | ✅ | **we're ahead** |
| int16 quant, two-level IVF | ✅ | ✅ | done |
| Exact bbox branch-and-bound | ✅ | ✅ | **v1.7.0** |
| Cell-split (small tight cells) | ✅ (1024) | ✅ (256) | **v1.7.0** |
| Pre-rendered responses | ✅ | ✅ | done |
| HAProxy LB | ✅ | ✅ (TCP) | done |
| Warmup | ✅ | ✅ | done |
| Zero-alloc byte-scan vectorizer | ✅ | ❌ | **gap → port** |
| Custom NIO server (no framework) | ✅ | ❌ (Ktor CIO) | **gap → port** |
| UDS transport (−1ms) | ✅ | ⚠️ (failed health check) | **fixable** |
| cpuset + HAProxy tuning | ✅ | ⚠️ | **quick win** |
| PGO | ❌ | ❌ | greenfield spike |
| mmap/madvise/huge-pages | (superseded) | ❌ | skip |
| FFM C/AVX2 kernel | (spike only) | ❌ | skip |

## Prioritized roadmap to top-50

We already have **0 errors via exact search** + working SIMD. The remaining gap to his p99 is
the **request edge** (allocation + transport). Ranked:

1. **HAProxy tuning + cpuset** — near-zero effort, copy `leastconn`/`http-reuse always`/
   `http-no-delay`/`no log`, add `cpuset`. Do first as a cheap experiment.
2. **Zero-alloc byte-scan vectorizer** (port `SchemaAwareVectorizationStrategy` to Kotlin) —
   biggest p99-tail win; removes kotlinx.serialization allocation. Must match reference float
   parse + 4-decimal clamp (0-error preservation).
3. **Custom Kotlin NIO server** (port `NioEventLoop`+`HttpRequestParser`) — highest structural
   ceiling (his +0.7ms + zero-alloc steady state). Drop Ktor. Do with #2 (shared scratch model).
4. **Fix UDS health check** (tmpfs socket dir + 0666 + stale-socket cleanup + HAProxy UDS
   `check`) — worth −1ms if the check passes.
5. **PGO spike** — greenfield differentiator; needs Oracle GraalVM. After 1–4.

**Do NOT port:** FFM C kernel (we have native SIMD he lacks), mmap/madvise (superseded),
`IVF_EARLY_STOP` probabilistic stop (off in the 0-error path; would reintroduce errors), the
dead Vector-API-over-double path.

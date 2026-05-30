
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
}

group = "dev.santo"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "dev.santo.bootstrap.MainKt"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Compile-time generated serializers (no reflection). Pulled in directly
    // instead of via Ktor's `ktor-serialization-kotlinx-json` wrapper, which
    // drags in ktor-serialization + ktor-websockets + kotlin-reflect (~4.7MB
    // of native image) — and we never install ContentNegotiation.
    implementation(libs.kotlinx.serialization.json)
    implementation(ktorLibs.server.cio)
    implementation(ktorLibs.server.core)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

// Defensive: kotlin-reflect (~4.7MB) is build-time only for the Kotlin compiler,
// never something the app needs at runtime — exclude it from the runtime/native
// classpaths so a transitive cannot silently re-bloat the native image.
configurations.matching {
    it.name in setOf(
        "runtimeClasspath",
        "nativeImageClasspath",
        "nativeImageCompileClasspath",
    )
}.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// Offline IVF recall/cost calibration over the 3M references (never shipped).
// Run: ./gradlew ivfCalibrate -Pargs="build/refs-3m.json.gz 8000 4096 16"
tasks.register<JavaExec>("ivfCalibrate") {
    group = "verification"
    description = "Offline IVF recall/cost calibration over the reference dataset."
    mainClass.set("dev.santo.tools.IvfCalibrateKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "10g"
    (project.findProperty("args") as String?)?.let { args = it.trim().split(" ") }
}

graalvmNative {
    binaries.named("main") {
        imageName.set("rinha-server")
        mainClass.set("dev.santo.bootstrap.MainKt")
        buildArgs.add("--no-fallback")
        // Reachability metadata captured by the native-image agent at build time
        // (see Dockerfile.native), plus a manual supplement for Ktor CIO's
        // AtomicReferenceFieldUpdater fields the agent's tracer drops.
        buildArgs.add("-H:ConfigurationFileDirectories=/app/native-config")
        buildArgs.add("-H:ReflectionConfigurationFiles=/app/native-config/manual-reflect-config.json")
        // Aggressive optimization (Community Edition). The submission is CPU-bound on
        // the contest's 0.425-CPU budget; the previous quickBuild image shipped an
        // unoptimized binary that wasted exactly the CPU it could not spare.
        buildArgs.add("-O3")
        // Target the SSE4.2 baseline (x86-64-v2): safe on the contest Mac Mini (Late
        // 2014, Haswell) and on the CI builder. Never use -march=native — the CI CPU
        // may emit instructions the Mac Mini lacks, crashing with SIGILL at runtime.
        buildArgs.add("-march=x86-64-v2")
        // Align the runtime with the cgroup CPU quota (0.425 ≈ 1 core). Without this,
        // the GC / ForkJoin / CIO pools size to the host's core count and the CFS quota
        // throttles them in bursts (frozen ~58ms every 100ms), which spikes p99.
        buildArgs.add("-R:ActiveProcessorCount=1")
        // Cap the heap so the ~90MB resident index plus per-request churn never races
        // the 160MB cgroup limit against off-heap/code/stacks (the plain JVM OOM-killed
        // here; see CLAUDE.md). Leaves ~40MB headroom inside the limit.
        buildArgs.add("-R:MaxHeapSize=120m")
        // Fully optimized final image (was true — see -O3 above).
        quickBuild.set(false)
    }
    metadataRepository {
        enabled.set(true)
    }
}


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
    // The SIMD distance kernel (search.BlockDistance, Java) imports jdk.incubator.vector;
    // the incubator module must be added to every JVM that runs the compiled code.
    applicationDefaultJvmArgs = listOf("--add-modules", "jdk.incubator.vector")
}

kotlin {
    jvmToolchain(25)
}

// jdk.incubator.vector is an incubator module: it must be added at javac and at any
// JVM (test, run, the offline JavaExec tools) that loads the compiled kernel.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}
tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.vector")
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

// Offline measurement of the int16 quantization floor vs the float gabarito (never shipped).
tasks.register<JavaExec>("quantFloor") {
    group = "verification"
    description = "Measure the int16 exact 5-NN floor vs the float gabarito — is zero error reachable?"
    mainClass.set("dev.santo.tools.QuantFloorKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "10g"
    (project.findProperty("args") as String?)?.let { args = it.trim().split(" ") }
}

// Quick local smoke: build a small IVF2 artifact from the example refs (never shipped).
tasks.register<JavaExec>("buildSmoke") {
    group = "verification"
    description = "Build a tiny IVF2 index from example references for a local server smoke test."
    mainClass.set("dev.santo.tools.BuildIndexKt")
    classpath = sourceSets["main"].runtimeClasspath
    environment("IVF_K", "64")
    args = listOf("src/test/resources/example-references.json", "build/smoke.bin")
}

// Local IVF build over the 3M refs with CPU cap + cell-split, for offline measurement.
// Run: ./gradlew buildIvf -PivfK=4096 -PmaxCell=512 -Ppar=4 -Pargs="build/refs-3m.json.gz build/ivf-split.bin"
tasks.register<JavaExec>("buildIvf") {
    group = "verification"
    description = "Build an IVF index (with cell-split) over the references, CPU-capped."
    mainClass.set("dev.santo.tools.BuildIndexKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "10g"
    environment("IVF_K", (project.findProperty("ivfK") as String?) ?: "4096")
    environment("IVF_MAX_CELL", (project.findProperty("maxCell") as String?) ?: "512")
    environment("IVF_META_CELLS", (project.findProperty("metaCells") as String?) ?: "128")
    environment("IVF_PARALLELISM", (project.findProperty("par") as String?) ?: "4")
    (project.findProperty("args") as String?)?.let { args = it.trim().split(" ") }
}

// Offline cost measurement of the EXACT bbox branch-and-bound search (never shipped).
// Run: ./gradlew exactProbe -Pargs="build/ivf-k4096-i15.bin build/gold-N5000-fr0.5.bin 5000 4"
tasks.register<JavaExec>("exactProbe") {
    group = "verification"
    description = "Measure exact bbox-pruned IVF search cost (points scanned p99) + error over 3M."
    mainClass.set("dev.santo.tools.ExactProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "10g"
    (project.findProperty("args") as String?)?.let { args = it.trim().split(" ") }
}

// Offline measurement of two-level (district→cell) IVF routing (never shipped).
tasks.register<JavaExec>("ivfTwoLevel") {
    group = "verification"
    description = "Measure two-level IVF routing comps + recall over the reference dataset."
    mainClass.set("dev.santo.tools.IvfTwoLevelKt")
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
        // Enable the SIMD distance kernel (search.BlockDistance). GraalVM 25 only
        // emits real machine SIMD for the Vector API at an AVX2 target — an SSE-only
        // build slow-emulates it ~230-300× (measured: see the SIMD gate). So the
        // module flag is paired with the v3 march below; together they give ~6.5×.
        buildArgs.add("--add-modules")
        buildArgs.add("jdk.incubator.vector")
        buildArgs.add("-H:+UnlockExperimentalVMOptions")
        buildArgs.add("-H:+VectorAPISupport")
        // Target AVX2 (x86-64-v3), NOT the former v2 baseline: the contest Mac Mini
        // (Late 2014) is Haswell, which has AVX2, and the public 5881-score entry won
        // with hand-written AVX2 SIMD on this same contest — so v3 is safe here and is
        // REQUIRED for the Vector API kernel to vectorize. Never use -march=native.
        // (Risk: hardware without AVX2 would SIGILL — but a preview is reversible.)
        buildArgs.add("-march=x86-64-v3")
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

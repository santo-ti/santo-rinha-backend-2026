
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
}

group = "dev.santo"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "dev.santo.MainKt"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // ktor-serialization-kotlinx-json brings kotlinx-serialization-json transitively;
    // the routes use the compile-time generated serializers directly (no reflection),
    // so ContentNegotiation is not installed.
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.cio)
    implementation(ktorLibs.server.core)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

graalvmNative {
    binaries.named("main") {
        imageName.set("rinha-server")
        mainClass.set("dev.santo.MainKt")
        buildArgs.add("--no-fallback")
        // Reachability metadata captured by the native-image agent at build time
        // (see Dockerfile.native), plus a manual supplement for Ktor CIO's
        // AtomicReferenceFieldUpdater fields the agent's tracer drops.
        buildArgs.add("-H:ConfigurationFileDirectories=/app/native-config")
        buildArgs.add("-H:ReflectionConfigurationFiles=/app/native-config/manual-reflect-config.json")
        // Faster builds while iterating; drop for a fully optimized final image.
        quickBuild.set(true)
    }
    metadataRepository {
        enabled.set(true)
    }
}

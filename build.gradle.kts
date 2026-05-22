
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.cio)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

# syntax=docker/dockerfile:1

# --- Builder: produce the Ktor fat jar via the project's own Gradle wrapper ---
# Build natively (fast on ARM); the resulting jar is platform-independent bytecode.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

# Warm the dependency cache in its own layer so source-only changes rebuild fast.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon buildFatJar

# --- Runtime: JRE-only image pinned to linux/amd64 as required by the rules ---
FROM --platform=linux/amd64 eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

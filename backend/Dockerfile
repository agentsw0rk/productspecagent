# syntax=docker/dockerfile:1.7

FROM harbor.eos.netrtl.com/docker/library/eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

FROM harbor.eos.netrtl.com/docker/library/eclipse-temurin:21-jre AS prod
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-Dotel.exporter.otlp.endpoint=https://signoz-otelcollector.operations-dev-internal.k8s.netrtl.com", "-Dotel.service.name=productspecagent-backend-service", "-Dotel.traces.exporter=otlp", "-Dotel.metrics.exporter=otlp", "-Dotel.logs.exporter=otlp", "-jar", "/app/app.jar"]

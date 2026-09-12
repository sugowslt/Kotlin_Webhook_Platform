# syntax=docker/dockerfile:1

FROM gradle:9.3.1-jdk17-alpine AS build
WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle build.gradle.kts settings.gradle.kts ./
COPY --chown=gradle:gradle src ./src
USER gradle
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S hookrelay && adduser -S -G hookrelay hookrelay
COPY --from=build --chown=hookrelay:hookrelay /workspace/build/libs/hook-relay-0.0.1-SNAPSHOT.jar app.jar
USER hookrelay
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

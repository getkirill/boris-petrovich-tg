# ---- Stage 1: Build ----
FROM gradle:8.14-jdk21 AS builder
WORKDIR /app

# don't redownload dependencies each time
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

RUN ./gradlew dependencies --no-daemon

COPY src src

RUN ./gradlew shadowJar --no-daemon --info

# ---- Stage 2: Run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /app/build/libs/*-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
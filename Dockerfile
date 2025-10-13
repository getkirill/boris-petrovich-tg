# ---- Stage 1: Build ----
FROM gradle:8.14-jdk21 AS builder
WORKDIR /app

# Copy everything from Dockerfile directory into build context
COPY . .

# Build the Shadow Jar (skipping tests for faster build, optional)
RUN gradle shadowJar --no-daemon --info

# ---- Stage 2: Run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built shadow jar from the builder stage
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Default command to run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
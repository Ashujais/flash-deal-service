FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

# Copy gradle wrapper and config
COPY gradlew settings.gradle.kts build.gradle.kts /app/
COPY gradle /app/gradle

# Grant execution permissions
RUN chmod +x gradlew

# Copy source code
COPY src /app/src

# Build distribution
RUN ./gradlew --no-daemon installDist

# Production runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install curl for optional health checks
RUN apk add --no-cache curl bash

# Copy application distribution
COPY --from=builder /app/build/install/nnomm-flash-deal /app

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/app/bin/nnomm-flash-deal"]

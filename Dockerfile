# Multi-stage build for Adous application
# Stage 1: Build the application
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy source code
COPY src src

# Make gradlew executable and build the application
RUN chmod +x gradlew && ./gradlew clean build -x test

# Stage 2: Create the runtime image
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership to adous user
RUN chown -R adous:adous /app

# Switch to non-root user
USER adous:adous

# Expose the application port
EXPOSE 8080

# Set JVM options for optimal performance
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseContainerSupport"

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]


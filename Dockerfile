# Multi-stage Dockerfile for CERMAT (Spring Boot + Java 17)
# Stage 1: Build application
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Cache dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Build artifact
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Security: run as non-root user
RUN addgroup -S cermat && adduser -S cermat -G cermat
USER cermat

# Copy JAR from builder stage
COPY --from=builder /build/target/cermat-*.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Xss512k", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

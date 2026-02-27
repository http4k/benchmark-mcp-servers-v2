FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /app

# Install Gradle
RUN microdnf install findutils wget unzip \
    && wget -q https://services.gradle.org/distributions/gradle-9.3.1-bin.zip \
    && unzip -q gradle-9.3.1-bin.zip \
    && rm gradle-9.3.1-bin.zip

COPY build.gradle settings.gradle ./
COPY src ./src

# Build JAR (JVM mode)
RUN ./gradle-9.3.1/bin/gradle clean build -x test --no-daemon

# Final image — Eclipse Temurin JRE 25
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

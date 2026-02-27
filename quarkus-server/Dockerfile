FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y wget unzip && rm -rf /var/lib/apt/lists/* \
    && wget -q https://services.gradle.org/distributions/gradle-8.14-bin.zip \
    && unzip -q gradle-8.14-bin.zip \
    && rm gradle-8.14-bin.zip

COPY build.gradle settings.gradle gradle.properties ./
COPY src ./src

RUN ./gradle-8.14/bin/gradle build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/build/quarkus-app/ /app/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/q/health || exit 1

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]

FROM gradle:8.14-jdk21-alpine AS builder

WORKDIR /app

# Copiar arquivos de build
COPY build.gradle settings.gradle ./
COPY src ./src

# Build da aplicação
RUN gradle clean build -x test --no-daemon

# Imagem final
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copiar JAR da imagem builder
COPY --from=builder /app/build/libs/*.jar app.jar

# Expor porta
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Executar aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]

package com.benchmark.mcp

import org.http4k.config.EnvironmentKey

val API_SERVICE_URL = EnvironmentKey.defaulted("API_SERVICE_URL", "http://mcp-api-service:8100")
val REDIS_URL = EnvironmentKey.defaulted("REDIS_URL", "redis://mcp-redis:6379")
val SERVER_PORT = EnvironmentKey.defaulted("SERVER_PORT", "8097")


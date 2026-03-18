package com.benchmark.mcp

import com.benchmark.mcp.endpoints.HealthCheck
import com.benchmark.mcp.tools.Checkout
import com.benchmark.mcp.tools.GetUserCart
import com.benchmark.mcp.tools.SearchProducts
import io.lettuce.core.RedisClient.create
import io.lettuce.core.api.sync.RedisCommands
import org.http4k.ai.mcp.model.McpEntity
import org.http4k.ai.mcp.protocol.ServerMetaData
import org.http4k.ai.mcp.protocol.Version
import org.http4k.ai.mcp.server.security.NoMcpSecurity
import org.http4k.client.JavaHttpClient
import org.http4k.config.Environment.Companion.ENV
import org.http4k.core.HttpHandler
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.routing.mcpHttpNonStreaming
import org.http4k.routing.routes
import org.http4k.server.Netty
import org.http4k.server.asServer

fun BenchmarkServer(api: HttpHandler, redis: RedisCommands<String, String>) = routes(
    HealthCheck(),
    mcpHttpNonStreaming(
        ServerMetaData(McpEntity.of("benchmark-mcp-http4k-server"), Version.of("1.0.0")),
        NoMcpSecurity,
        SearchProducts(api, redis),
        GetUserCart(api, redis),
        Checkout(api, redis)
    )
)

fun main() {
    val apiClient = ClientFilters.SetBaseUriFrom(Uri.of(API_SERVICE_URL(ENV))).then(JavaHttpClient())
    val redis = create(REDIS_URL(ENV)).connect().sync()

    BenchmarkServer(apiClient, redis).asServer(Netty(SERVER_PORT(ENV).toInt())).start()
}

package com.benchmark.mcp.tools

import com.benchmark.mcp.AppJson
import io.lettuce.core.api.sync.RedisCommands
import org.http4k.ai.mcp.model.Tool
import org.http4k.ai.mcp.model.string
import org.http4k.ai.mcp.server.capability.ToolCapability
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.routing.bind
import se.ansman.kotshi.JsonSerializable
import java.util.concurrent.CompletableFuture

fun GetUserCart(api: HttpHandler, redis: RedisCommands<String, String>): ToolCapability {
    val userId = Tool.Arg.string().defaulted("user_id", "user-00042", "User identifier")

    return Tool(
        "get_user_cart",
        "Get user cart details with recent order history",
        userId
    ) bind { request ->
        val user = userId(request)
        val cartKey = "bench:cart:$user"
        val histKey = "bench:history:$user"

        val cartHash = redis.hgetall(cartKey)

        val items: List<Map<String, Any>> = cartHash["items"]
            ?.let { AppJson.asA(it) }
            ?: emptyList()

        val firstProductId = items.firstOrNull()
            ?.let { (it["product_id"] as? Number)?.toInt() } ?: 1

        val apiFuture = CompletableFuture.supplyAsync { api(Request(GET, "/products/$firstProductId")) }
        val historyRaw = redis.lrange(histKey, 0, 4)
        apiFuture.join()

        val recentHistory: List<Map<String, Any>> = historyRaw.mapNotNull { entry -> AppJson.asA(entry) }

        val estimatedTotal = cartHash["total"]?.toDoubleOrNull() ?: 0.0

        UserCartResult(
            user,
            Cart(items, items.size, estimatedTotal),
            recentHistory
        ).toToolResponse()
    }
}

@JsonSerializable
data class Cart(val items: List<Map<String, Any>>, val item_count: Int, val estimated_total: Double)

@JsonSerializable
data class UserCartResult(val user_id: String, val cart: Cart, val recent_history: List<Map<String, Any>>, val server_type: String = "http4k")

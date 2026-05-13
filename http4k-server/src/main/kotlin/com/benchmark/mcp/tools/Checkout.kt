package com.benchmark.mcp.tools

import com.benchmark.mcp.AppJson
import com.benchmark.mcp.AppJson.auto
import com.benchmark.mcp.AppJson.json
import io.lettuce.core.api.sync.RedisCommands
import org.http4k.ai.mcp.model.Tool
import org.http4k.ai.mcp.model.string
import org.http4k.ai.mcp.server.capability.ToolCapability
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.routing.bind
import se.ansman.kotshi.JsonSerializable
import java.util.concurrent.CompletableFuture

fun Checkout(api: HttpHandler, redis: RedisCommands<String, String>): ToolCapability {
    val userId = Tool.Arg.string().defaulted("user_id", "user-00042", "User identifier")
    val items = Tool.Arg.auto<List<Map<String, Any>>>(listOf()).map<List<CheckoutItem>>(
        nextIn = { raw -> raw.map(CheckoutItem::fromMap) },
        nextOut = { items -> items.map(CheckoutItem::toMap) }
    ).required("items", "List of items to checkout")

    return Tool(
        "checkout",
        "Process checkout: calculate total, update rate limit, record history",
        userId, items
    ) bind { request ->
        val user = userId(request)

        val userNum = user.split("-").lastOrNull()?.toIntOrNull() ?: 42
        val rateKey = "bench:ratelimit:user-%05d".format(userNum % 100)
        val histKey = "bench:history:$user"
        val items = items(request)
        val productId = items.firstOrNull()?.product_id ?: 42
        val ts = System.currentTimeMillis() / 1000
        val orderId = "ORD-$user-$ts"

        val calcFuture = CompletableFuture.supplyAsync {
            api(Request(POST, "/cart/calculate").json(
                mapOf(
                    "user_id" to user,
                    "items" to items
                )
            ))
        }

        val orderEntry = AppJson.asFormatString(OrderEntry(orderId, items, ts))

        val rateCount = redis.run {
            rpush(histKey, orderEntry)
            zincrby("bench:popular", 1.0, "product:$productId")
            incr(rateKey)
        }

        val calcData = calcFuture.join().json<CalcResponse>()
        Order(
            calcData.order_id ?: orderId,
            user,
            calcData.total ?: 0.0,
            items.size,
            rateCount
        ).toToolResponse()
    }
}

@JsonSerializable
data class OrderEntry(
    val order_id: String,
    val items: List<CheckoutItem>,
    val td: Long
)

@JsonSerializable
data class CheckoutItem(val product_id: Int, val quantity: Int) {
    fun toMap(): Map<String, Any> = mapOf("product_id" to product_id, "quantity" to quantity)

    companion object {
        fun fromMap(m: Map<String, Any>) = CheckoutItem(
            product_id = (m["product_id"] as Number).toInt(),
            quantity = (m["quantity"] as Number).toInt()
        )
    }
}

@JsonSerializable
data class CalcResponse(val total: Double?, val order_id: String?)

@JsonSerializable
data class Order(
    val order_id: String,
    val user_id: String,
    val total: Double,
    val items_count: Int,
    val rate_limit_count: Long,
    val status: String = "confirmed",
    val server_type: String = "http4k"
)
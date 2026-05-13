package com.benchmark.mcp.tools

import com.benchmark.mcp.AppJson.json
import io.lettuce.core.api.sync.RedisCommands
import org.http4k.ai.mcp.model.Tool
import org.http4k.ai.mcp.model.float
import org.http4k.ai.mcp.model.int
import org.http4k.ai.mcp.model.string
import org.http4k.ai.mcp.server.capability.ToolCapability
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.routing.bind
import se.ansman.kotshi.JsonSerializable

fun SearchProducts(api: HttpHandler, redis: RedisCommands<String, String>): ToolCapability {
    val category = Tool.Arg.string().defaulted("category", "Electronics", "Product category")
    val minPrice = Tool.Arg.float().defaulted("min_price", 50.0f, "Minimum price")
    val maxPrice = Tool.Arg.float().defaulted("max_price", 500.0f, "Maximum price")
    val limit = Tool.Arg.int().defaulted("limit", 10, "Max results")

    return Tool(
        "search_products",
        "Search products by category and price range, merged with popularity data",
        category, minPrice, maxPrice, limit
    ) bind { request ->
        val cat = category(request)

        val searchData = api(
            Request(GET, Uri.of("/products/search"))
                .query("category", cat)
                .query("min_price", minPrice(request).toString())
                .query("max_price", maxPrice(request).toString())
                .query("limit", limit(request).toString())
        ).json<SearchResponse>()

        val parsed = redis.zrevrange("bench:popular", 0, 9)
            .mapIndexedNotNull { index, member ->
                member.split(":", limit = 2)
                    .takeIf { it.size == 2 }
                    ?.let { it[1].toIntOrNull() }
                    ?.let { id -> id to (index + 1) }
            }
        val top10Set = parsed.toMap()

        val products = searchData.products.map { p ->
            ProductResult(
                id = p.id,
                sku = p.sku,
                name = p.name,
                price = p.price,
                rating = p.rating,
                popularity_rank = top10Set[p.id] ?: 0
            )
        }

        SearchResult(
            cat,
            searchData.total_found,
            products,
            parsed.map { it.first }
        ).toToolResponse()
    }
}

@JsonSerializable
data class SearchResponse(val products: List<ApiProduct>, val total_found: Int)

@JsonSerializable
data class ApiProduct(val id: Int, val sku: String, val name: String, val price: Double, val rating: Double)

@JsonSerializable
data class ProductResult(val id: Int, val sku: String, val name: String, val price: Double, val rating: Double, val popularity_rank: Int)

@JsonSerializable
data class SearchResult(
    val category: String,
    val total_found: Int,
    val products: List<ProductResult>,
    val top10_popular_ids: List<Int>,
    val server_type: String = "http4k"
)

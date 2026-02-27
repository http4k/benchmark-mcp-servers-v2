package com.benchmark.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class BenchmarkTools {

    @Inject
    @Client("${API_SERVICE_URL:http://mcp-api-service:8100}")
    HttpClient httpClient;

    @Inject
    StatefulRedisConnection<String, String> redisConnection;

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "search_products", description = "Search products by category and price range, merged with popularity data")
    public String searchProducts(
            @ToolArg(name = "category", description = "Product category") String category,
            @ToolArg(name = "min_price", description = "Minimum price") double minPrice,
            @ToolArg(name = "max_price", description = "Maximum price") double maxPrice,
            @ToolArg(name = "limit", description = "Result limit") int limit) {

        final String cat = (category == null || category.isBlank()) ? "Electronics" : category;
        final double min = minPrice == 0 ? 50.0 : minPrice;
        final double max = maxPrice == 0 ? 500.0 : maxPrice;
        final int lim = limit == 0 ? 10 : limit;

        // Fire Redis ZREVRANGE async while HTTP call runs
        var async = redisConnection.async();
        var popularFut = async.zrevrange("bench:popular", 0, 9);

        // Blocking HTTP call
        String uri = "/products/search?category=" + cat
                + "&min_price=" + min + "&max_price=" + max + "&limit=" + lim;
        @SuppressWarnings("unchecked")
        Map<String, Object> searchData = httpClient.toBlocking()
                .retrieve(HttpRequest.GET(uri).accept(MediaType.APPLICATION_JSON_TYPE), Map.class);

        // Collect Redis result
        List<String> popularRaw;
        try { popularRaw = popularFut.toCompletableFuture().join(); } catch (Exception e) { popularRaw = List.of(); }

        var top10Ids = popularRaw.stream()
                .filter(m -> m.startsWith("product:"))
                .map(m -> Integer.parseInt(m.substring("product:".length())))
                .collect(Collectors.toList());
        var top10Set = new HashMap<Integer, Integer>();
        for (int i = 0; i < top10Ids.size(); i++) top10Set.put(top10Ids.get(i), i + 1);

        @SuppressWarnings("unchecked")
        var rawProducts = (List<Map<String, Object>>) searchData.get("products");
        var products = rawProducts == null ? List.of() : rawProducts.stream()
                .map(p -> {
                    int pid = ((Number) p.get("id")).intValue();
                    var item = new HashMap<String, Object>();
                    item.put("id", pid);
                    item.put("sku", p.get("sku"));
                    item.put("name", p.get("name"));
                    item.put("price", p.get("price"));
                    item.put("rating", p.get("rating"));
                    item.put("popularity_rank", top10Set.getOrDefault(pid, 0));
                    return item;
                })
                .collect(Collectors.toList());

        var result = new HashMap<String, Object>();
        result.put("category", cat);
        result.put("total_found", searchData.get("total_found"));
        result.put("products", products);
        result.put("top10_popular_ids", top10Ids);
        result.put("server_type", "micronaut");
        try { return mapper.writeValueAsString(result); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    @Tool(name = "get_user_cart", description = "Get user cart details with recent order history")
    public String getUserCart(
            @ToolArg(name = "user_id", description = "User ID") String userId) {

        final String uid = (userId == null || userId.isBlank()) ? "user-00042" : userId;

        // Step 1: HGETALL (sync)
        Map<String, String> cartHash = redisConnection.sync()
                .hgetall("bench:cart:" + uid);

        var itemsJson = cartHash.getOrDefault("items", "[]");
        List<Map<String, Object>> items;
        try {
            items = mapper.readValue(itemsJson, mapper.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            items = List.of();
        }
        int firstProductId = items.isEmpty() ? 1
                : ((Number) items.get(0).get("product_id")).intValue();
        double estimatedTotal = 0.0;
        try { estimatedTotal = Double.parseDouble(cartHash.getOrDefault("total", "0")); }
        catch (Exception ignored) {}

        // Step 2a+2b: async Redis LRANGE while HTTP runs
        var async = redisConnection.async();
        var histFut = async.lrange("bench:history:" + uid, 0, 4);

        @SuppressWarnings("unchecked")
        Map<String, Object> product = httpClient.toBlocking().retrieve(
                HttpRequest.GET("/products/" + firstProductId)
                        .accept(MediaType.APPLICATION_JSON_TYPE),
                Map.class);

        List<String> historyRaw;
        try { historyRaw = histFut.toCompletableFuture().join(); } catch (Exception e) { historyRaw = List.of(); }

        var recentHistory = historyRaw.stream()
                .map(entry -> {
                    try { return (Object) mapper.readValue(entry, Map.class); }
                    catch (Exception e) { return Map.of("raw", entry); }
                })
                .collect(Collectors.toList());

        var cart = new HashMap<String, Object>();
        cart.put("items", items);
        cart.put("item_count", items.size());
        cart.put("estimated_total", estimatedTotal);

        var result = new HashMap<String, Object>();
        result.put("user_id", uid);
        result.put("cart", cart);
        result.put("recent_history", recentHistory);
        result.put("server_type", "micronaut");
        try { return mapper.writeValueAsString(result); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    @Tool(name = "checkout", description = "Process checkout: calculate total, update rate limit, record history")
    public String checkout(
            @ToolArg(name = "user_id", description = "User ID") String userId,
            @ToolArg(name = "items", description = "Items to purchase") List<Map<String, Object>> items) {

        final String uid = (userId == null || userId.isBlank()) ? "user-00042" : userId;
        final List<Map<String, Object>> finalItems = (items == null || items.isEmpty())
                ? List.of(Map.of("product_id", 42, "quantity", 2),
                          Map.of("product_id", 1337, "quantity", 1))
                : items;

        String[] parts = uid.split("-");
        int userNum = Integer.parseInt(parts[parts.length - 1]);
        String rateKey = String.format("bench:ratelimit:user-%05d", userNum % 100);
        String histKey = "bench:history:" + uid;
        int productId = ((Number) finalItems.get(0).get("product_id")).intValue();

        String orderEntry;
        try {
            orderEntry = mapper.writeValueAsString(Map.of(
                    "order_id", String.format("ORD-%s-%d", uid, Instant.now().getEpochSecond()),
                    "items", finalItems,
                    "ts", Instant.now().getEpochSecond()));
        } catch (Exception e) {
            orderEntry = "{}";
        }

        // Fire all Redis ops async
        var async = redisConnection.async();
        var rateFut = async.incr(rateKey);
        var histFut = async.rpush(histKey, orderEntry);
        var popularFut = async.zincrby("bench:popular", 1.0, "product:" + productId);

        // HTTP POST while Redis commands are in-flight
        var calcBody = new HashMap<String, Object>();
        calcBody.put("user_id", uid);
        calcBody.put("items", finalItems);
        @SuppressWarnings("unchecked")
        Map<String, Object> calcData = httpClient.toBlocking().retrieve(
                HttpRequest.POST("/cart/calculate", calcBody)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE),
                Map.class);

        // Collect Redis results
        long rateCount = 0;
        try { rateCount = rateFut.toCompletableFuture().join(); } catch (Exception ignored) {}
        try { histFut.toCompletableFuture().join(); } catch (Exception ignored) {}
        try { popularFut.toCompletableFuture().join(); } catch (Exception ignored) {}

        double total = calcData == null ? 0.0
                : ((Number) calcData.getOrDefault("total", 0.0)).doubleValue();
        String orderId = calcData == null ? "ORD-unknown"
                : (String) calcData.getOrDefault("order_id", "ORD-unknown");

        var result = new HashMap<String, Object>();
        result.put("order_id", orderId);
        result.put("user_id", uid);
        result.put("total", total);
        result.put("items_count", finalItems.size());
        result.put("rate_limit_count", rateCount);
        result.put("status", "confirmed");
        result.put("server_type", "micronaut");
        try { return mapper.writeValueAsString(result); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }
}

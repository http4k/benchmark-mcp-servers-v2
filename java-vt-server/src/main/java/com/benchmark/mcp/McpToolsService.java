package com.benchmark.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpToolsService {

    private final RestClient restClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpToolsService(StringRedisTemplate redis, RestClient restClient) {
        this.redis = redis;
        this.restClient = restClient;
    }

    @McpTool(name = "search_products", description = "Search products by category and price range, merged with popularity data")
    public Map<String, Object> searchProducts(
            @McpToolParam(description = "Product category") String category,
            @McpToolParam(description = "Minimum price") Double min_price,
            @McpToolParam(description = "Maximum price") Double max_price,
            @McpToolParam(description = "Result limit") Integer limit) {

        if (category == null || category.isEmpty()) category = "Electronics";
        if (min_price == null || min_price == 0) min_price = 50.0;
        if (max_price == null || max_price == 0) max_price = 500.0;
        if (limit == null || limit == 0) limit = 10;

        @SuppressWarnings("unchecked")
        Map<String, Object> searchData = restClient.get()
                .uri("/products/search?category={c}&min_price={min}&max_price={max}&limit={l}",
                        category, min_price, max_price, limit)
                .retrieve().body(Map.class);

        var popularRaw = redis.opsForZSet().reverseRange("bench:popular", 0, 9);

        List<Integer> top10Ids = popularRaw == null ? List.of() : popularRaw.stream()
                .map(m -> {
                    String[] parts = m.split(":");
                    return parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
                })
                .toList();
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
                .toList();

        var response = new HashMap<String, Object>();
        response.put("category", category);
        response.put("total_found", searchData.get("total_found"));
        response.put("products", products);
        response.put("top10_popular_ids", top10Ids);
        response.put("server_type", "java-vt");
        return response;
    }

    @McpTool(name = "get_user_cart", description = "Get user cart details with recent order history")
    public Map<String, Object> getUserCart(
            @McpToolParam(description = "User ID") String user_id) {

        if (user_id == null || user_id.isEmpty()) user_id = "user-00042";

        @SuppressWarnings("unchecked")
        var cartHash = (Map<Object, Object>) (Map<?, ?>) redis.opsForHash().entries("bench:cart:" + user_id);
        var itemsJson = (String) cartHash.getOrDefault("items", "[]");
        List<Map<String, Object>> items;
        try {
            items = mapper.readValue(itemsJson, mapper.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            items = List.of();
        }
        final int firstProductId = items.isEmpty() ? 1
                : ((Number) items.get(0).get("product_id")).intValue();

        double estimatedTotal = 0.0;
        try { estimatedTotal = Double.parseDouble((String) cartHash.getOrDefault("total", "0")); }
        catch (Exception ignored) {}

        restClient.get().uri("/products/{id}", firstProductId).retrieve().toBodilessEntity();

        var historyRaw = redis.opsForList().range("bench:history:" + user_id, 0, 4);

        var recentHistory = historyRaw == null ? List.of() : historyRaw.stream()
                .map(entry -> {
                    try { return (Object) mapper.readValue(entry, Map.class); }
                    catch (Exception e) { return Map.of("raw", entry); }
                })
                .toList();

        var cart = new HashMap<String, Object>();
        cart.put("items", items);
        cart.put("item_count", items.size());
        cart.put("estimated_total", estimatedTotal);

        var response = new HashMap<String, Object>();
        response.put("user_id", user_id);
        response.put("cart", cart);
        response.put("recent_history", recentHistory);
        response.put("server_type", "java-vt");
        return response;
    }

    @McpTool(name = "checkout", description = "Process checkout: calculate total, update rate limit, record history")
    public Map<String, Object> checkout(
            @McpToolParam(description = "User ID") String user_id,
            @McpToolParam(description = "Items to purchase") List<Map<String, Object>> items) {

        if (user_id == null || user_id.isEmpty()) user_id = "user-00042";
        if (items == null || items.isEmpty()) {
            items = List.of(
                    Map.of("product_id", 42, "quantity", 2),
                    Map.of("product_id", 1337, "quantity", 1));
        }

        String[] parts = user_id.split("-");
        int userNum = Integer.parseInt(parts[parts.length - 1]);
        final String rateKey = String.format("bench:ratelimit:user-%05d", userNum % 100);
        final String histKey = "bench:history:" + user_id;
        final int productId = ((Number) items.get(0).get("product_id")).intValue();

        final String orderEntry;
        try {
            orderEntry = mapper.writeValueAsString(Map.of(
                    "order_id", String.format("ORD-%s-%d", user_id, Instant.now().getEpochSecond()),
                    "items", items,
                    "ts", Instant.now().getEpochSecond()));
        } catch (Exception e) {
            return Map.of("error", "serialization failed");
        }

        final var calcBody = new HashMap<String, Object>();
        calcBody.put("user_id", user_id);
        calcBody.put("items", items);

        @SuppressWarnings("unchecked")
        Map<String, Object> calcData = restClient.post()
                .uri("/cart/calculate")
                .body(calcBody)
                .retrieve()
                .body(Map.class);

        Long rateCount = redis.opsForValue().increment(rateKey);
        redis.opsForList().rightPush(histKey, orderEntry);
        redis.opsForZSet().incrementScore("bench:popular", "product:" + productId, 1.0);

        double total = calcData == null ? 0.0
                : ((Number) calcData.getOrDefault("total", 0.0)).doubleValue();
        String orderId = calcData == null ? "ORD-unknown"
                : (String) calcData.getOrDefault("order_id", "ORD-unknown");

        var response = new HashMap<String, Object>();
        response.put("order_id", orderId);
        response.put("user_id", user_id);
        response.put("total", total);
        response.put("items_count", items.size());
        response.put("rate_limit_count", rateCount != null ? rateCount : 0L);
        response.put("status", "confirmed");
        response.put("server_type", "java-vt");
        return response;
    }
}

package com.benchmark.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class McpToolsService {

    private final WebClient webClient;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpToolsService(ReactiveStringRedisTemplate redis, WebClient webClient) {
        this.redis = redis;
        this.webClient = webClient;
    }

    @McpTool(name = "search_products", description = "Search products by category and price range, merged with popularity data")
    public Mono<Map<String, Object>> searchProducts(
            @McpToolParam(description = "Product category") String category,
            @McpToolParam(description = "Minimum price") Double min_price,
            @McpToolParam(description = "Maximum price") Double max_price,
            @McpToolParam(description = "Result limit") Integer limit) {

        final String cat = (category == null || category.isEmpty()) ? "Electronics" : category;
        final double min = (min_price == null || min_price == 0) ? 50.0 : min_price;
        final double max = (max_price == null || max_price == 0) ? 500.0 : max_price;
        final int lim = (limit == null || limit == 0) ? 10 : limit;

        // Parallel: HTTP search + Redis popular
        Mono<Map> searchMono = webClient.get()
                .uri("/products/search?category={c}&min_price={min}&max_price={max}&limit={l}",
                        cat, min, max, lim)
                .retrieve()
                .bodyToMono(Map.class);

        Mono<List<String>> popularMono = redis.opsForZSet()
                .reverseRange("bench:popular", Range.closed(0L, 9L))
                .collectList();

        return Mono.zip(searchMono, popularMono).map(tuple -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> searchData = tuple.getT1();
            List<String> popularRaw = tuple.getT2();

            var top10Ids = popularRaw.stream()
                    .map(m -> {
                        String[] parts = m.split(":");
                        return parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
                    })
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

            var response = new HashMap<String, Object>();
            response.put("category", cat);
            response.put("total_found", searchData.get("total_found"));
            response.put("products", products);
            response.put("top10_popular_ids", top10Ids);
            response.put("server_type", "java-webflux");
            return (Map<String, Object>) response;
        });
    }

    @McpTool(name = "get_user_cart", description = "Get user cart details with recent order history")
    public Mono<Map<String, Object>> getUserCart(
            @McpToolParam(description = "User ID") String user_id) {

        final String uid = (user_id == null || user_id.isEmpty()) ? "user-00042" : user_id;

        return redis.opsForHash().entries("bench:cart:" + uid)
                .collectMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString())
                .flatMap(cartHash -> {
                    var itemsJson = cartHash.getOrDefault("items", "[]");
                    List<Map<String, Object>> items;
                    try {
                        items = mapper.readValue(itemsJson, mapper.getTypeFactory()
                                .constructCollectionType(List.class, Map.class));
                    } catch (Exception e) {
                        items = List.of();
                    }
                    final List<Map<String, Object>> finalItems = items;
                    int firstProductId = items.isEmpty() ? 1
                            : ((Number) items.get(0).get("product_id")).intValue();
                    double estimatedTotal = 0.0;
                    try { estimatedTotal = Double.parseDouble(cartHash.getOrDefault("total", "0")); }
                    catch (Exception ignored) {}
                    final double finalEstimatedTotal = estimatedTotal;

                    // Parallel: product HTTP + history Redis
                    Mono<Map> productMono = webClient.get()
                            .uri("/products/{id}", firstProductId)
                            .retrieve()
                            .bodyToMono(Map.class);

                    Mono<List<String>> historyMono = redis.opsForList()
                            .range("bench:history:" + uid, 0, 4)
                            .collectList();

                    return Mono.zip(productMono, historyMono).map(tuple -> {
                        var historyRaw = tuple.getT2();
                        var recentHistory = historyRaw.stream()
                                .map(entry -> {
                                    try { return (Object) mapper.readValue(entry, Map.class); }
                                    catch (Exception e) { return Map.of("raw", entry); }
                                })
                                .collect(Collectors.toList());

                        var cart = new HashMap<String, Object>();
                        cart.put("items", finalItems);
                        cart.put("item_count", finalItems.size());
                        cart.put("estimated_total", finalEstimatedTotal);

                        var response = new HashMap<String, Object>();
                        response.put("user_id", uid);
                        response.put("cart", cart);
                        response.put("recent_history", recentHistory);
                        response.put("server_type", "java-webflux");
                        return (Map<String, Object>) response;
                    });
                });
    }

    @McpTool(name = "checkout", description = "Process checkout: calculate total, update rate limit, record history")
    public Mono<Map<String, Object>> checkout(
            @McpToolParam(description = "User ID") String user_id,
            @McpToolParam(description = "Items to purchase") List<Map<String, Object>> items) {

        final String uid = (user_id == null || user_id.isEmpty()) ? "user-00042" : user_id;
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
        final String finalOrderEntry = orderEntry;

        var calcBody = new HashMap<String, Object>();
        calcBody.put("user_id", uid);
        calcBody.put("items", finalItems);

        // All 4 I/O operations in parallel via Mono.zip
        Mono<Map> calcMono = webClient.post()
                .uri("/cart/calculate")
                .bodyValue(calcBody)
                .retrieve()
                .bodyToMono(Map.class);

        Mono<Long> rateMono = redis.opsForValue().increment(rateKey);
        Mono<Long> histMono = redis.opsForList().rightPush(histKey, finalOrderEntry);
        Mono<Double> popularMono = redis.opsForZSet()
                .incrementScore("bench:popular", "product:" + productId, 1);

        return Mono.zip(calcMono, rateMono, histMono, popularMono).map(tuple -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> calcData = tuple.getT1();
            long rateCount = tuple.getT2();

            double total = calcData == null ? 0.0
                    : ((Number) calcData.getOrDefault("total", 0.0)).doubleValue();
            String orderId = calcData == null ? "ORD-unknown"
                    : (String) calcData.getOrDefault("order_id", "ORD-unknown");

            var response = new HashMap<String, Object>();
            response.put("order_id", orderId);
            response.put("user_id", uid);
            response.put("total", total);
            response.put("items_count", finalItems.size());
            response.put("rate_limit_count", rateCount);
            response.put("status", "confirmed");
            response.put("server_type", "java-webflux");
            return (Map<String, Object>) response;
        });
    }
}

package com.benchmark.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class BenchmarkTools {

    @Inject
    @RestClient
    ApiServiceClient apiClient;

    @Inject
    ReactiveRedisDataSource redis;

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "search_products", description = "Search products by category and price range, merged with popularity data")
    public Uni<String> searchProducts(
            @ToolArg(name = "category") String category,
            @ToolArg(name = "min_price") double minPrice,
            @ToolArg(name = "max_price") double maxPrice,
            @ToolArg(name = "limit") int limit) {

        final String cat = (category == null || category.isBlank()) ? "Electronics" : category;
        final double min = minPrice == 0 ? 50.0 : minPrice;
        final double max = maxPrice == 0 ? 500.0 : maxPrice;
        final int lim = limit == 0 ? 10 : limit;

        Uni<Map<String, Object>> searchUni = apiClient.searchProducts(cat, min, max, lim);
        Uni<List<String>> popularUni = redis.sortedSet(String.class)
                .zrange("bench:popular", 0, 9, new ZRangeArgs().rev());

        return Uni.combine().all().unis(searchUni, popularUni).asTuple()
                .map(tuple -> {
                    var searchData = tuple.getItem1();
                    var popularRaw = tuple.getItem2();

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
                    result.put("server_type", "quarkus");
                    try {
                        return mapper.writeValueAsString(result);
                    } catch (Exception e) {
                        return "{\"error\":\"serialization failed\"}";
                    }
                });
    }

    @Tool(name = "get_user_cart", description = "Get user cart details with recent order history")
    public Uni<String> getUserCart(@ToolArg(name = "user_id") String userId) {

        final String uid = (userId == null || userId.isBlank()) ? "user-00042" : userId;

        return redis.hash(String.class).hgetall("bench:cart:" + uid)
                .flatMap(cartHash -> {
                    var itemsJson = cartHash.getOrDefault("items", "[]");
                    List<Map<String, Object>> items;
                    try {
                        items = mapper.readValue(itemsJson, mapper.getTypeFactory()
                                .constructCollectionType(List.class, Map.class));
                    } catch (Exception e) {
                        items = List.of();
                    }
                    final var finalItems = items;
                    int firstProductId = items.isEmpty() ? 1
                            : ((Number) items.get(0).get("product_id")).intValue();
                    double estimatedTotal = 0.0;
                    try { estimatedTotal = Double.parseDouble(cartHash.getOrDefault("total", "0")); }
                    catch (Exception ignored) {}
                    final double finalTotal = estimatedTotal;

                    Uni<Map<String, Object>> productUni = apiClient.getProduct(firstProductId);
                    Uni<List<String>> historyUni = redis.list(String.class)
                            .lrange("bench:history:" + uid, 0, 4);

                    return Uni.combine().all().unis(productUni, historyUni).asTuple()
                            .map(tuple -> {
                                var historyRaw = tuple.getItem2();
                                var recentHistory = historyRaw.stream()
                                        .map(entry -> {
                                            try { return (Object) mapper.readValue(entry, Map.class); }
                                            catch (Exception e) { return Map.of("raw", entry); }
                                        })
                                        .collect(Collectors.toList());

                                var cart = new HashMap<String, Object>();
                                cart.put("items", finalItems);
                                cart.put("item_count", finalItems.size());
                                cart.put("estimated_total", finalTotal);

                                var result = new HashMap<String, Object>();
                                result.put("user_id", uid);
                                result.put("cart", cart);
                                result.put("recent_history", recentHistory);
                                result.put("server_type", "quarkus");
                                try {
                                    return mapper.writeValueAsString(result);
                                } catch (Exception e) {
                                    return "{\"error\":\"serialization failed\"}";
                                }
                            });
                });
    }

    @Tool(name = "checkout", description = "Process checkout: calculate total, update rate limit, record history")
    public Uni<String> checkout(
            @ToolArg(name = "user_id") String userId,
            @ToolArg(name = "items") List<Map<String, Object>> items) {

        final String uid = (userId == null || userId.isBlank()) ? "user-00042" : userId;
        @SuppressWarnings("unchecked")
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

        Uni<Map<String, Object>> calcUni = apiClient.calculateCart(calcBody);
        Uni<Long> rateUni = redis.value(String.class).incr(rateKey);
        Uni<Long> histUni = redis.list(String.class).rpush(histKey, finalOrderEntry);
        Uni<Double> popularUni = redis.sortedSet(String.class)
                .zincrby("bench:popular", 1.0, "product:" + productId);

        return Uni.combine().all().unis(calcUni, rateUni, histUni, popularUni).asTuple()
                .map(tuple -> {
                    @SuppressWarnings("unchecked")
                    var calcData = tuple.getItem1();
                    long rateCount = tuple.getItem2() != null ? tuple.getItem2() : 0L;

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
                    result.put("server_type", "quarkus");
                    try {
                        return mapper.writeValueAsString(result);
                    } catch (Exception e) {
                        return "{\"error\":\"serialization failed\"}";
                    }
                });
    }
}

# Adding a New Server to the Benchmark

The MCP Server Performance Benchmark is designed to be easily extensible. Depending on your goal, you might want to add a variation of an existing implementation (e.g., changing the compiler, runtime, or framework) or an entirely new programming language to the suite.

Follow the instructions below depending on your use case.

---

## Tool Contract (I/O-bound)

All servers must implement exactly **3 tools** that perform real I/O against two shared infrastructure services:

- **`api-service`** — Go HTTP server on `http://mcp-api-service:8100` with a 100k-product catalog
- **`redis`** — Redis 7 on `mcp-redis:6379`, pre-seeded with cart data, order history, and popularity rankings

### Tool 1: `search_products`

```
Params:
  category:  string  (default: "Electronics")
  min_price: float   (default: 50.0)
  max_price: float   (default: 500.0)
  limit:     int     (default: 10)

I/O:
  1. GET http://mcp-api-service:8100/products/search?category=...&min_price=...&max_price=...&limit=...
  2. ZREVRANGE bench:popular 0 9   (top-10 popular product IDs)
  → merge popularity rank into each product

Returns: { category, total_found, products:[{id,sku,name,price,rating,popularity_rank}],
           top10_popular_ids, server_type }
```

**Validation check:** `total_found == 2251` (Electronics, $50–$500) and `products.length == 10`.

---

### Tool 2: `get_user_cart`

```
Params:
  user_id: string  (default: "user-00042")

I/O:
  1. HGETALL bench:cart:{user_id}
  2a. GET http://mcp-api-service:8100/products/{first_product_id}   ← parallel
  2b. LRANGE bench:history:{user_id} 0 4                           ← parallel

Returns: { user_id, cart:{items,item_count,estimated_total}, recent_history, server_type }
```

**Validation check:** `cart.items.length >= 1` and `recent_history.length == 5`.

Steps 2a and 2b should be executed **in parallel** where the concurrency model allows it (async/await, reactive streams, goroutines, etc.). This is the key differentiating tool between concurrency models.

---

### Tool 3: `checkout`

```
Params:
  user_id: string                            (default: "user-00042")
  items:   [{product_id: int, quantity: int}] (default: [{42,2},{1337,1}])

I/O:
  1. POST http://mcp-api-service:8100/cart/calculate   ← parallel
  2. INCR bench:ratelimit:user-{N%100:05d}             ← parallel
  3. RPUSH bench:history:{user_id} {order_json}        ← parallel
  4. ZADD bench:popular INCR 1 product:{product_id}    ← parallel

Returns: { order_id, user_id, total, items_count, rate_limit_count, status:"confirmed", server_type }
```

**Validation check:** `status == "confirmed"`, `total > 0`, `items_count == 2`.

Redis ops 2–4 should be fired concurrently with the HTTP POST where possible.

---

## Environment Variables

Every MCP server container must have these environment variables set (already configured in `docker-compose.yml`):

```yaml
environment:
  - REDIS_URL=redis://mcp-redis:6379
  - API_SERVICE_URL=http://mcp-api-service:8100
```

---

## Port Map

| Server | Port |
|--------|------|
| java | 8080 |
| go | 8081 |
| python | 8082 |
| nodejs | 8083 |
| java-native | 8084 |
| quarkus | 8085 |
| quarkus-native | 8086 |
| java-vt | 8087 |
| java-vt-native | 8088 |
| java-webflux | 8089 |
| java-webflux-native | 8090 |
| micronaut | 8091 |
| micronaut-native | 8092 |
| python-granian | 8093 |
| bun | 8094 |
| rust | 8095 |
| rust-axum | 8096 |
| *(next available)* | 8097+ |

---

## Scenario 1: Variation of an Existing Server

If you want to test the same code with a different runtime or configuration (e.g., JVM vs GraalVM Native Image, Node.js vs Bun, uvicorn vs Granian), **do not duplicate the source code folder**. Reuse the existing folder with a unique `Dockerfile`.

### Steps:

1. **Create a specific Dockerfile** inside the existing server folder:
   ```
   java-server/Dockerfile.native
   python-server/Dockerfile.granian
   nodejs-server/Dockerfile.bun
   ```

2. **Update `docker-compose.yml`** — add a new service pointing to the same context with the new Dockerfile and a new port. Include the infra env vars and network membership (`mcp-net`):
   ```yaml
   my-server-variation:
     build:
       context: ./my-existing-server
       dockerfile: Dockerfile.variation
     container_name: mcp-my-server-variation
     ports:
       - "8097:8080"
     environment:
       - SERVER_TYPE=my-variation
       - REDIS_URL=redis://mcp-redis:6379
       - API_SERVICE_URL=http://mcp-api-service:8100
     networks:
       - mcp-net
     depends_on:
       mcp-redis:
         condition: service_healthy
       mcp-api-service:
         condition: service_healthy
     deploy:
       resources:
         limits:
           cpus: '2'
           memory: 2G
     healthcheck:
       test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
       interval: 5s
       timeout: 3s
       retries: 5
   ```

3. **Update `benchmark/run_benchmark.sh`** — add to the `SERVERS` map:
   ```bash
   [my-variation]="mcp-my-server-variation:8097"
   ```
   And add `my-variation` to `SELECTED_SERVERS` default list.

4. **Update `benchmark/consolidate.py`** — add `'my-variation'` to the `CANONICAL_ORDER` list.

5. **Update `test_mcp_servers.py`** — add to the `SERVERS` dict:
   ```python
   "my-variation": "http://localhost:8097/mcp",
   ```

6. **Document** the addition in `README.md`.

---

## Scenario 2: Adding a New Technology / Language

Create a new isolated directory and implement the 3 I/O tools against `api-service` and Redis.

### Steps:

1. **Create a new directory** at the project root:
   ```
   my-lang-server/
     src/
     Dockerfile
     Cargo.toml  # or go.mod, package.json, pom.xml, etc.
   ```

2. **Implement the 3 tools** (`search_products`, `get_user_cart`, `checkout`) as specified in the Tool Contract above. Key requirements:
   - Read `API_SERVICE_URL` and `REDIS_URL` from environment variables
   - Default values when parameters are null/missing
   - Return JSON with `"server_type": "my-lang"` field
   - Expose MCP endpoint at `/mcp`
   - Expose health check at `/health` (returns `{"status":"ok"}`)
   - **Parallelize I/O** where possible: in `get_user_cart` the HTTP call and Redis LRANGE should be concurrent; in `checkout` the 3 Redis ops should be concurrent with the HTTP POST

3. **Create a `Dockerfile`:**
   ```dockerfile
   # Example: multi-stage build
   FROM builder-image AS builder
   WORKDIR /app
   COPY . .
   RUN build-command

   FROM runtime-image
   COPY --from=builder /app/binary /usr/local/bin/
   EXPOSE 8097
   CMD ["binary"]
   ```

4. **Integrate into `docker-compose.yml`** (same template as Scenario 1).

5. **Update the 3 scripts** (`run_benchmark.sh`, `consolidate.py`, `test_mcp_servers.py`) as in Scenario 1.

6. **Validate your implementation:**
   ```bash
   # Start infrastructure first
   docker compose up -d mcp-redis mcp-api-service

   # Seed Redis (required before any test)
   docker compose run --rm redis-seeder

   # Build and start your server
   docker compose build my-lang-server
   docker compose up -d my-lang-server

   # Run the validation tests
   python3 test_mcp_servers.py
   ```

   You should see `✅ ALL PASSED for MY-LANG` before proceeding to the full benchmark.

7. **Run the benchmark:**
   ```bash
   ./benchmark/run_benchmark.sh my-lang
   ```

---

## Tips for I/O Parallelism

The `get_user_cart` tool is the key differentiator between concurrency models. Here are the patterns used in each existing implementation:

| Runtime | Pattern |
|---------|---------|
| Go | `sync.WaitGroup` / goroutines |
| Rust (tokio) | `tokio::join!()` |
| Python asyncio | `asyncio.gather()` |
| Node.js | `Promise.all()` |
| Java WebFlux | `Mono.zip()` |
| Java VT | `Thread.ofVirtual()` / `CompletableFuture` |
| Quarkus | `Uni.combine().all().unis(...).asTuple()` |
| Micronaut | fire Lettuce `async()` commands → blocking HTTP → `.toCompletableFuture().join()` |

Servers that run the HTTP call and Redis LRANGE sequentially will have measurably higher `get_user_cart` latency.

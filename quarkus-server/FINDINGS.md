# Quarkus MCP Server — Benchmark Findings

## Benchmark environment
- 50 VUs, 5 min, 2 CPUs, 2 GB RAM, sleep(0.05) per iteration (High Pressure)
- Results: `benchmark/results/20260224_222710/`

---

## Finding 1 — Catastrophic Network Failure (30s Timeout) Under High Load

### Symptom
During the initial high-pressure benchmark runs (`sleep 0.05` instead of the classic `0.5`), the Quarkus server failed completely, registering less than 1 RPS.
The K6 test reported extensive Timeout errors, and the container logs revealed that all outward requests to the backend server (`mcp-api-service`) were failing at exactly the `30000ms` mark. This suggested Quarkus was internally queuing connections rather than executing them.

Other competing frameworks (like Spring WebFlux and Micronaut) passed the same test flawlessly with 2500+ RPS.

### Root Cause: Artificial HTTP Connection Pool Limit in Rest Client (Vert.x)
Quarkus uses its native `REST Client Reactive` (based on annotations like `@RegisterRestClient`), which operates on top of the non-blocking Vert.x architecture. However, this engine ships with highly conservative connection pool configurations by default (often anchored at 50 max connections per host for fail-fast safety reasons).

By injecting dozens of full sessions simultaneously via K6 (requiring session openings for Initialize, then Notification, and finally requests to the catalog API), the default 50 connection quota was instantly exhausted. Quarkus then placed subsequent K6 requests into an asynchronous waiting queue. As the queue stalled, these requests "waited" for a slot in the outward TCP connection pool until the configured 30-second window expired, resulting in 100% client timeouts and 0 effective worker processing.

### Action
We had to apply aggressive tuning in `application.properties` to "unlock" the network throughput, raising the outward pool to 1000 connections:
```properties
quarkus.rest-client.api-service.connection-pool-size=1000
quarkus.rest-client.api-service.keep-alive-enabled=true
```

---

## Finding 2 — Secondary Bottleneck: Redis Client Wait Queue

### Symptom
After fixing the HTTP pool, we recompiled the image and ran it again. The 30s Timeout disappeared, confirming our previous diagnosis. However, the requests started failing with "500 Internal Server Error", plummeting the RPS again.
Checking the internal logs consistently revealed this specific error:
`Unable to call tool get_user_cart: io.vertx.core.http.ConnectionPoolTooBusyException: Connection pool reached max wait queue size of 24`

### Root Cause: Default Wait-Queue limit in Mutiny/Vert.x for Redis
Unlocking the 1000 HTTP ports merely shifted the "hose bottleneck" to the state I/O layer: the Redis client (which Quarkus also manages reactively via Vert.x).
By default, the Quarkus/Vert.x Redis client allows a certain number of active parallel connections but strictly permits a maximum of **24 requests to wait in the execution queue** (*wait queue*). With 50 continuous VUs firing without pauses (`sleep 0.05`), the threads triggered asynchronous Get/Set requests for the Session in Redis simultaneously, instantly breaching the queue limit of 24 and triggering the `ConnectionPoolTooBusyException`.

### Action
We implemented another tuning step in the application properties, raising both the explicit *pool-size* limit and the *wait-queue* margin (100 heavy Redis connections open and a 1000 in-memory waiting margin).
```properties
quarkus.redis.max-pool-size=100
quarkus.redis.max-pool-waiting=1000
```

---

## Finding 3 — Tuned Quarkus (Efficient Resurrection)

After unleashing the Connection Pools of its native Reactive implementation (Vert.x), Quarkus splendidly absorbed the load of 50 VUs (0.05s).

**Rescued Performance:**
* Final RPS: **2,698** (Surpassed WebFlux and Virtual Threads, technically tying with the mighty Rust)
* P95 Latency: **107ms** (Average: 28.4ms)
* CPU Consumption: **48.6%** (Surprisingly low)
* Memory RAM: **171 MB** (Lowest JVM Footprint of the Spring/Micronaut/Quarkus battery)

By allowing Quarkus to process I/O to the very hardware limit of the machine without imposing software queue restrictions, it proved to be **the Reactive implementation on the JVM with the best CPU/RAM cost-benefit**. The use of 48% CPU compared to the 110% of the Spring/Reactor environment highlights the overhead excellence of the Vert.x/Mutiny engine compared to its peers in the Java world, once appropriately configured for high throughput (network-bound).

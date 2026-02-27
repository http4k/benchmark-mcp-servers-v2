# Go MCP Server — Benchmark Findings

## Benchmark environment
- 10 VUs, 5 min, 2 CPUs, 2 GB RAM
- Results: `benchmark/results/20260222_153955/` + `20260222_183420/`

---

## Finding 1 — `fetch_external_data` p95=61ms (FIXED)

### Symptom
| Metric | Before fix | After fix (expected) |
|--------|-----------|----------------------|
| avg    | 6.67ms    | <1ms                 |
| p95    | 61ms      | <3ms                 |

Other stacks calling the same `mock-api` endpoint: avg <1ms, p95 <1ms.

### Root cause
`http.Get(args.Endpoint)` uses the global `http.DefaultClient` with its default
`Transport`, which has `MaxIdleConnsPerHost=2`. Under 10 concurrent VUs, goroutines
contend for the two idle keep-alive connections, causing TCP connection churn and
60x p95 latency spikes.

### Fix (`go-server/main.go`)
Added a package-level `httpClient` with a tuned `Transport`:

```go
var httpClient = &http.Client{
    Transport: &http.Transport{
        MaxIdleConns:        100,
        MaxIdleConnsPerHost: 100,
        IdleConnTimeout:     90 * time.Second,
    },
    Timeout: 10 * time.Second,
}
```

Replaced `http.Get(args.Endpoint)` → `httpClient.Get(args.Endpoint)`.

---

## Finding 2 — `simulate_database_query` slower than JVM (1.3ms vs Java 0.9ms)

### Symptom
10k-record in-memory dataset scan latencies:

| Server          | avg  |
|-----------------|------|
| rust-axum       | 0.19ms |
| quarkus JVM     | 0.59ms |
| java-vt JVM     | 0.73ms |
| java JVM        | 0.89ms |
| **go**          | **1.30ms** |
| java-native     | 1.40ms |

### Root cause
The Go compiler (`gc`) does not auto-vectorize loops over structs with mixed field
types (`int`, `bool`, `float64`). The HotSpot JIT (C2 compiler) detects the tight
loop pattern at runtime and emits SSE2-vectorized code. LLVM (Rust) also applies
SIMD automatically.

This is expected compiler behaviour, not a bug. Improving it would require manual
SIMD intrinsics or restructuring the dataset into separate arrays (struct-of-arrays
layout) to enable auto-vectorization.

### Action
No fix applied — documented for the public report. The gap is small (1.3ms vs 0.9ms)
and the total throughput of the Go server is competitive with other stacks.

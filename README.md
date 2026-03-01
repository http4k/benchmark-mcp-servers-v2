# MCP Server Performance Benchmark v2

A comprehensive experimental analysis comparing 15 Model Context Protocol (MCP) server implementations across Rust, Java (Spring MVC, WebFlux, Virtual Threads), Quarkus, Micronaut (JVM and GraalVM native images), Go, Bun, Node.js, and Python. Three independent runs totaling 39.9 million requests with I/O-bound workloads (Redis + HTTP API), 0% error rate across all servers.

## Results & Analysis

Full results, implementation details, and recommendations:
**[https://www.tmdevlab.com/mcp-server-performance-benchmark-v2.html](https://www.tmdevlab.com/mcp-server-performance-benchmark-v2.html)**

### Key Findings

| Server | RPS | Avg Latency | RAM avg |
|---|---|---|---|
| rust | 4,845 | 5.09 ms | 10.9 MB |
| quarkus | 4,739 | 4.04 ms | 194 MB |
| go | 3,616 | 6.87 ms | 23.9 MB |
| java | 3,540 | 6.13 ms | 368 MB |
| java-vt | 3,482 | 9.03 ms | 350 MB |
| quarkus-native | 3,449 | 10.36 ms | 36 MB |
| micronaut | 3,382 | 9.75 ms | 216 MB |
| java-webflux | 3,032 | 8.89 ms | 485 MB |
| java-vt-native | 2,447 | 19.06 ms | 194 MB |
| java-webflux-native | 2,413 | 14.43 ms | 351 MB |
| java-native | 2,316 | 16.20 ms | 178 MB |
| micronaut-native | 2,161 | 20.75 ms | 63 MB |
| bun | 876 | 48.46 ms | 549 MB |
| nodejs | 423 | 123.50 ms | 395 MB |
| python | 259 | 251.62 ms | 259 MB |

All 39.9 million requests completed with **0% errors** across all 15 servers and 3 runs.

## Project Structure

```
benchmark-mcp-servers-v2/
├── rust-server/              # rmcp 0.16 + Tokio + deadpool-redis
├── go-server/                # mcp-go + tuned http.Transport
├── java-server/              # Spring Boot 4 MVC (blocking thread pool)
├── java-vt-server/           # Spring Boot 4 + Project Loom Virtual Threads
├── java-webflux-server/      # Spring Boot 4 WebFlux (Reactor / Netty)
├── quarkus-server/           # Quarkus 3.31.4 (Vert.x / Mutiny)
├── micronaut-server/         # Micronaut 4.10.8 / MCP SDK 0.0.19
├── nodejs-server/            # MCP SDK + Express, WEB_CONCURRENCY=4
├── python-server/            # FastMCP + uvloop, 4 workers
├── api-service/              # Go HTTP API (100k in-memory products)
├── infra/                    # Redis configuration
├── benchmark/
│   ├── benchmark.js          # k6 load test (50 VUs, 5 min, 60s warmup)
│   ├── run_benchmark.sh      # Full benchmark orchestration script
│   ├── collect_stats.py      # Docker stats collection
│   ├── consolidate.py        # Results aggregation across runs
│   └── results/              # Raw results per run (summary.json, k6.json)
├── config/                   # Shared benchmark configuration
├── build-server-images.sh    # Build all server Docker images
├── docker-compose.yml
└── TMDevLab/                 # Published post (HTML/CSS/JS)
```

Native image variants (Quarkus, Java MVC, Java VT, WebFlux, Micronaut) are built from separate Dockerfiles inside each server directory (`Dockerfile.native`).

## Benchmark Workload

Each server implements three tools backed by real I/O:

| Tool | Operations |
|---|---|
| `search_products` | HTTP GET `/products/search` + Redis ZRANGE (parallel) |
| `get_user_cart` | Redis HGETALL + HTTP GET `/products/{id}` + Redis LRANGE (parallel) |
| `checkout` | HTTP POST `/cart/calculate` + Redis pipeline INCR+RPUSH+ZADD (parallel) |

**Test configuration:** 50 VUs, 5-minute sustained load, 60-second warmup excluded, Redis flushed and re-seeded before each server test, 2 vCPUs / 2 GB per container.

## Running the Benchmark

### Prerequisites

- Docker and Docker Compose
- Python 3 (for stats collection and consolidation)
- k6 (optional — the orchestration script uses a containerized runner)

### Build Images

```bash
./build-server-images.sh
```

### Run Full Benchmark

```bash
cd benchmark
./run_benchmark.sh
```

Results are saved to `benchmark/results/<timestamp>/` with a `summary.json` per run.

### Run a Single Server Manually

```bash
# Start infrastructure
docker-compose up -d mcp-redis mcp-api-service

# Start the target server
docker-compose up -d mcp-rust-server

# Run k6
k6 run -e SERVER_URL=http://localhost:8095/mcp benchmark/benchmark.js
```

### Server Ports

| Server | Port |
|---|---|
| api-service | 8100 |
| java | 8080 |
| java-native | 8084 |
| go | 8081 |
| python | 8082 |
| nodejs | 8083 |
| bun | 8094 |
| rust | 8095 |
| quarkus | 8085 |
| quarkus-native | 8086 |
| java-vt | 8087 |
| java-vt-native | 8088 |
| java-webflux | 8089 |
| java-webflux-native | 8090 |
| micronaut | 8091 |
| micronaut-native | 8092 |

## Benchmark Rounds

Results from the three v2 runs (February 27–28, 2026) are in `bench-round-2.md`. Earlier exploration rounds are in `bench-round-0.md` and `bench-round-1.md`.

An isolated run of the Rust server without the `json_response` SDK patch is available in `benchmark/results/20260301_151912/` for reference.

## Adding a New Server

See [ADDING_SERVERS.md](ADDING_SERVERS.md) for instructions on integrating a new implementation into the benchmark suite.

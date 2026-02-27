#!/usr/bin/env bash
set -euo pipefail

# ─── Configuration ────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Servers to benchmark (name:container:port)
declare -A SERVERS=(
    [python]="mcp-python-server:8082"
    [go]="mcp-go-server:8081"
    [nodejs]="mcp-nodejs-server:8083"
    [java]="mcp-java-server:8080"
    [java-native]="mcp-java-native-server:8084"
    [quarkus]="mcp-quarkus-server:8085"
    [quarkus-native]="mcp-quarkus-native-server:8086"
    [java-vt]="mcp-java-vt-server:8087"
    [java-vt-native]="mcp-java-vt-native-server:8088"
    [java-webflux]="mcp-java-webflux-server:8089"
    [java-webflux-native]="mcp-java-webflux-native-server:8090"
    [micronaut]="mcp-micronaut-server:8091"
    [micronaut-native]="mcp-micronaut-native-server:8092"
    [bun]="mcp-bun-server:8094"
    [rust]="mcp-rust-server:8095"
)

# Colors
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}   $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERR]${NC}  $*"; }

# ─── Infrastructure ───────────────────────────────────────────────────

start_infra() {
    info "Starting infrastructure services (redis + api-service)..."
    cd "$PROJECT_DIR"
    docker compose up -d mcp-redis mcp-api-service

    # Wait for redis
    info "Waiting for Redis to be ready..."
    local elapsed=0
    while ! docker compose exec -T mcp-redis redis-cli ping > /dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge 30 ]; then
            error "Redis failed to start after 30s"
            exit 1
        fi
    done
    ok "Redis ready"

    # Wait for api-service
    info "Waiting for api-service to be ready..."
    elapsed=0
    while ! curl -sf -m 2 "http://localhost:8100/health" > /dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge 30 ]; then
            error "api-service failed to start after 30s"
            exit 1
        fi
    done
    ok "api-service ready"
}

seed_redis() {
    info "Seeding Redis (10k carts, 1k histories, 100k ZADD, 100 counters)..."
    cd "$PROJECT_DIR"
    # Check if already seeded (bench:popular should have 100k members)
    local count
    count=$(docker compose exec -T mcp-redis redis-cli ZCARD bench:popular 2>/dev/null || echo "0")
    if [ "$count" -ge 100000 ]; then
        ok "Redis already seeded (bench:popular has $count members), skipping."
        return 0
    fi

    docker compose run --rm redis-seeder
    ok "Redis seeding complete"
}

reset_redis() {
    # Flush all benchmark data and re-seed for a clean state before each server test.
    # This prevents history lists from growing unboundedly across runs and eliminates
    # order bias: all servers start from the same Redis baseline.
    info "Resetting Redis state (flush + re-seed)..."
    cd "$PROJECT_DIR"
    docker compose exec -T mcp-redis redis-cli FLUSHDB > /dev/null 2>&1
    docker compose run --rm redis-seeder 2>&1 | grep -E '(Seeded|Error|error)' || true
    ok "Redis reset and re-seeded"
}

stop_infra() {
    info "Stopping infrastructure services..."
    cd "$PROJECT_DIR"
    docker compose stop mcp-redis mcp-api-service 2>/dev/null || true
    ok "Infrastructure stopped"
}

# ─── Functions ────────────────────────────────────────────────────────

wait_for_health() {
    local port=$1
    local name=$2
    local max_wait=60
    local elapsed=0

    info "Waiting for $name to be ready (port $port)..."
    while true; do
        # Try /health (Go, Node.js, Rust)
        if curl -sf -m 2 "http://localhost:$port/health" > /dev/null 2>&1; then
            break
        fi
        # Try /actuator/health (Java Spring)
        if curl -sf -m 2 "http://localhost:$port/actuator/health" > /dev/null 2>&1; then
            break
        fi
        # Try /q/health (Quarkus)
        if curl -sf -m 2 "http://localhost:$port/q/health" > /dev/null 2>&1; then
            break
        fi
        # Try MCP endpoint (Python — no health endpoint, but MCP responds)
        if curl -sf -m 2 -X POST "http://localhost:$port/mcp" \
            -H "Content-Type: application/json" \
            -H "Accept: application/json, text/event-stream" \
            -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"health","version":"1.0"}}}' \
            > /dev/null 2>&1; then
            break
        fi
        sleep 1
        elapsed=$((elapsed + 1))
        if [ $elapsed -ge $max_wait ]; then
            error "$name failed to start after ${max_wait}s"
            return 1
        fi
    done
    ok "$name is ready (${elapsed}s)"
}

warmup() {
    # Comprehensive warmup:
    # 1. Basic initialize requests to establish connections
    # 2. Full MCP sessions for each tool to trigger JIT compilation on all code paths
    # The k6 script additionally excludes the first 60s from metrics (WARMUP_SECONDS=60).
    local url=$1
    local name=$2
    info "Warming up $name (5 init + 3 sessions per tool)..."

    # Phase 1: basic initialize (5x) — connection pool warm-up
    for i in $(seq 1 5); do
        curl -sf -X POST "$url" \
            -H "Content-Type: application/json" \
            -H "Accept: application/json, text/event-stream" \
            -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"warmup","version":"1.0"}}}' \
            > /dev/null 2>&1 || true
    done

    # Phase 2: full tool sessions (3x per tool) — JIT warmup on tool handler code paths
    local tool_payloads=(
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_products","arguments":{"category":"Electronics","min_price":50.0,"max_price":500.0,"limit":10}}}'
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_user_cart","arguments":{"user_id":"user-00042"}}}'
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"checkout","arguments":{"user_id":"user-00042","items":[{"product_id":42,"quantity":2},{"product_id":1337,"quantity":1}]}}}'
    )

    for payload in "${tool_payloads[@]}"; do
        for i in $(seq 1 3); do
            # Get session ID from initialize response headers
            local init_resp
            init_resp=$(curl -si -X POST "$url" \
                -H "Content-Type: application/json" \
                -H "Accept: application/json, text/event-stream" \
                -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"warmup","version":"1.0"}}}' \
                2>/dev/null || true)

            local session_id
            session_id=$(echo "$init_resp" | grep -i 'mcp-session-id:' | tr -d '\r' | awk '{print $2}' || true)

            # Call the tool (with or without session ID)
            if [ -n "$session_id" ]; then
                curl -sf -X POST "$url" \
                    -H "Content-Type: application/json" \
                    -H "Accept: application/json, text/event-stream" \
                    -H "Mcp-Session-Id: $session_id" \
                    -d "$payload" > /dev/null 2>&1 || true
                # Cleanup session
                curl -sf -X DELETE "$url" \
                    -H "Mcp-Session-Id: $session_id" \
                    > /dev/null 2>&1 || true
            else
                curl -sf -X POST "$url" \
                    -H "Content-Type: application/json" \
                    -H "Accept: application/json, text/event-stream" \
                    -d "$payload" > /dev/null 2>&1 || true
            fi
        done
    done

    ok "Warmup complete (5 init + 9 tool sessions)"
}

stop_mcp_servers() {
    info "Stopping all MCP server containers..."
    cd "$PROJECT_DIR"
    # Stop only MCP server services (not redis/api-service infra)
    local services_to_stop
    services_to_stop=$(docker compose ps --services 2>/dev/null \
        | grep -v '^mcp-redis$' \
        | grep -v '^mcp-api-service$' \
        | grep -v '^redis-seeder$') || true
    if [ -n "$services_to_stop" ]; then
        docker compose stop $services_to_stop 2>/dev/null || true
    fi
    sleep 2
    ok "All MCP servers stopped"
}

start_server() {
    local service=$1
    info "Starting $service..."
    cd "$PROJECT_DIR"
    docker compose up -d "$service" 2>/dev/null
}

benchmark_server() {
    local name=$1
    local results_dir=$2
    local container_port=${SERVERS[$name]}
    local container="${container_port%%:*}"
    local port="${container_port##*:}"
    local service="${name}-server"
    local url="http://localhost:$port/mcp"
    local server_results="$results_dir/$name"

    mkdir -p "$server_results"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  BENCHMARKING: ${name^^}"
    echo "  Container: $container | Port: $port | URL: $url"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # 1. Reset Redis for a clean, consistent baseline across all servers
    reset_redis

    # 2. Stop MCP servers, start only the target (infra stays up)
    stop_mcp_servers
    start_server "$service"

    # 3. Wait for health
    if ! wait_for_health "$port" "$name"; then
        error "Skipping $name — failed to start"
        return 1
    fi

    # 4. Warmup: connects + triggers JIT on all tool code paths
    warmup "$url" "$name"

    # 5. Start stats collector in background
    info "Starting Docker stats collector..."
    python3 "$SCRIPT_DIR/collect_stats.py" "$container" "$server_results/stats.json" 1.0 &
    local stats_pid=$!
    sleep 1

    # 6. Run k6 benchmark
    info "Running k6 benchmark (50 VUs, 5m)..."
    k6 run \
        --env SERVER_URL="$url" \
        --env SERVER_NAME="$name" \
        --env OUTPUT_PATH="$server_results/k6.json" \
        "$SCRIPT_DIR/benchmark.js" \
        2>&1 | tee "$server_results/k6_console.log"

    # 7. Stop stats collector
    info "Stopping stats collector..."
    kill "$stats_pid" 2>/dev/null || true
    wait "$stats_pid" 2>/dev/null || true

    ok "Benchmark complete for ${name^^}"
}

shuffle_array() {
    # Fisher-Yates shuffle for a bash indexed array (passed by name reference)
    local -n _arr=$1
    local size=${#_arr[@]}
    for ((i = size - 1; i > 0; i--)); do
        local j=$(( RANDOM % (i + 1) ))
        local tmp="${_arr[i]}"
        _arr[i]="${_arr[j]}"
        _arr[j]="$tmp"
    done
}

# ─── Main ─────────────────────────────────────────────────────────────

main() {
    local RUNS=1
    local SHUFFLE=false
    local SELECTED_SERVERS=()

    # Parse CLI arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --runs)
                RUNS="$2"
                shift 2
                ;;
            --shuffle)
                SHUFFLE=true
                shift
                ;;
            *)
                if [[ -v SERVERS[$1] ]]; then
                    SELECTED_SERVERS+=("$1")
                else
                    error "Server '$1' not recognized. Available: ${!SERVERS[*]}"
                    exit 1
                fi
                shift
                ;;
        esac
    done

    if [ ${#SELECTED_SERVERS[@]} -eq 0 ]; then
        SELECTED_SERVERS=(python go nodejs bun java java-native quarkus quarkus-native java-vt java-vt-native java-webflux java-webflux-native micronaut micronaut-native rust)
    fi

    # Shuffle server order if requested (eliminates order bias without needing Redis resets
    # between runs, since reset_redis() already handles per-server state)
    if [ "$SHUFFLE" = true ]; then
        info "Shuffling server order..."
        shuffle_array SELECTED_SERVERS
    fi

    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║           MCP SERVERS BENCHMARK SUITE v4                   ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    echo "║  VUs: 50 | Duration: 5m | Warmup: 60s | CPU: 2 | RAM: 2G  ║"
    echo "║  Tools: search_products, get_user_cart, checkout           ║"
    printf "║  Runs: %-3s | Shuffle: %-5s                               ║\n" "$RUNS" "$SHUFFLE"
    echo "║  Servers: ${SELECTED_SERVERS[*]}"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""

    # Start infra once — stays up across all server cycles and all runs
    start_infra
    seed_redis

    local all_result_dirs=()

    for run_idx in $(seq 1 "$RUNS"); do
        local TIMESTAMP
        TIMESTAMP=$(date +%Y%m%d_%H%M%S)
        local RESULTS_DIR="$SCRIPT_DIR/results/$TIMESTAMP"
        mkdir -p "$RESULTS_DIR"

        if [ "$RUNS" -gt 1 ]; then
            echo ""
            echo "╔══════════════════════════════════════════════════════════════╗"
            printf "║  RUN %d of %d — %s                        ║\n" "$run_idx" "$RUNS" "$TIMESTAMP"
            echo "╚══════════════════════════════════════════════════════════════╝"
        fi

        # Benchmark each server
        for name in "${SELECTED_SERVERS[@]}"; do
            benchmark_server "$name" "$RESULTS_DIR" || warn "Failed to benchmark $name, continuing..."
        done

        # Stop all MCP servers after this run
        stop_mcp_servers

        # Consolidate results for this run
        echo ""
        info "Consolidating results for run $run_idx..."
        python3 "$SCRIPT_DIR/consolidate.py" "$RESULTS_DIR"

        all_result_dirs+=("$RESULTS_DIR")

        # Brief pause between runs (let system settle)
        if [ "$run_idx" -lt "$RUNS" ]; then
            info "Waiting 10s before next run..."
            sleep 10
        fi
    done

    stop_infra

    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║  BENCHMARK COMPLETE                                        ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    for dir in "${all_result_dirs[@]}"; do
        printf "║  Results: %-51s║\n" "$dir"
    done
    echo "╚══════════════════════════════════════════════════════════════╝"
}

main "$@"

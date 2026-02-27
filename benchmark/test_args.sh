#!/usr/bin/env bash
# Tests for run_benchmark.sh CLI argument parsing.
# No Docker, k6, or real servers needed.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_BENCHMARK="$SCRIPT_DIR/run_benchmark.sh"
PASS=0; FAIL=0

pass() { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
fail() { echo "  [FAIL] $1: $2"; FAIL=$((FAIL + 1)); }

# Extract script body without the final "main "$@"" invocation so we can
# source the definitions and call main ourselves with controlled stubs.
SCRIPT_BODY=$(awk '/^main "\$@"$/ { exit } { print }' "$RUN_BENCHMARK")

# run_in_stub <arg>...
#   Sources run_benchmark.sh (minus the main call), stubs all external
#   commands, runs main with the supplied args, and emits:
#       SERVER=<name>    (one line per selected server)
#       ALL=<ALL_SERVICES string>
run_in_stub() {
    (
        eval "$SCRIPT_BODY"

        # Stub every command that touches Docker / network / filesystem
        docker()           { :; }
        curl()             { :; }
        k6()               { :; }
        python3()          { :; }
        stop_all_servers() { :; }
        start_server()     { :; }
        wait_for_health()  { return 0; }
        warmup()           { :; }
        benchmark_server() { :; }

        main "$@" > /dev/null 2>&1 || true

        for _s in "${SELECTED_SERVERS[@]}"; do echo "SERVER=$_s"; done
        echo "ALL=$ALL_SERVICES"
    )
}

# ─── Test 1: Unknown server → exit 1 + "not recognized" ──────────────────────
t1() {
    local output exit_code
    output=$(bash "$RUN_BENCHMARK" servidor-invalido 2>&1) && exit_code=$? || exit_code=$?
    if [ "$exit_code" -ne 1 ]; then
        fail "Invalid server exit code" "got $exit_code, want 1"
        return
    fi
    if ! printf '%s\n' "$output" | grep -q "not recognized"; then
        fail "Invalid server message" "'not recognized' not found in output"
        return
    fi
    pass "Unknown server → exit 1 + 'not recognized'"
}

# ─── Test 2: java-vt + java-vt-native → SELECTED_SERVERS=(java-vt java-vt-native) ─
t2() {
    local result servers
    result=$(run_in_stub java-vt java-vt-native 2>/dev/null) || true
    servers=$(printf '%s\n' "$result" | grep '^SERVER=' | sed 's/^SERVER=//' | tr '\n' ' ' | sed 's/ $//')
    if [ "$servers" = "java-vt java-vt-native" ]; then
        pass "java-vt + java-vt-native → SELECTED_SERVERS=(java-vt java-vt-native)"
    else
        fail "Subset selection" "Expected 'java-vt java-vt-native', got '$servers'"
    fi
}

# ─── Test 3: No args → all 9 servers selected ─────────────────────────────────
t3() {
    local result all_ok=1
    result=$(run_in_stub 2>/dev/null) || true
    for s in python go nodejs java java-native quarkus quarkus-native java-vt java-vt-native; do
        printf '%s\n' "$result" | grep -q "^SERVER=$s$" || { all_ok=0; break; }
    done
    if [ "$all_ok" -eq 1 ]; then
        pass "No args → all 9 servers in SELECTED_SERVERS"
    else
        fail "No-args all servers" "Not all 9 servers found. Output:$(printf '%s\n' "$result" | grep '^SERVER=')"
    fi
}

# ─── Test 4: go → ALL_SERVICES="go-server" ────────────────────────────────────
t4() {
    local result all_services
    result=$(run_in_stub go 2>/dev/null) || true
    all_services=$(printf '%s\n' "$result" | grep '^ALL=' | sed 's/^ALL=//')
    if [ "$all_services" = "go-server" ]; then
        pass "go → ALL_SERVICES=go-server"
    else
        fail "go → ALL_SERVICES" "Expected 'go-server', got '$all_services'"
    fi
}

# ─── Runner ────────────────────────────────────────────────────────────────────
echo ""
echo "run_benchmark.sh — argument parsing tests"
echo "========================================="
t1
t2
t3
t4
echo "========================================="
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1

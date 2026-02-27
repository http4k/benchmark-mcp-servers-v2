#!/usr/bin/env python3
"""
Tests for consolidate.py — no external dependencies required.
Validates get_servers() and consolidate() using temporary directories.
"""

import importlib.util
import json
import os
import shutil
import sys
import tempfile

# ─── Load consolidate module without executing main() ─────────────────────────

_spec = importlib.util.spec_from_file_location(
    "consolidate",
    os.path.join(os.path.dirname(__file__), "consolidate.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

get_servers = _mod.get_servers
consolidate = _mod.consolidate
CANONICAL_ORDER = _mod.CANONICAL_ORDER

# ─── Helpers ──────────────────────────────────────────────────────────────────

MINIMAL_K6 = {
    "server": "PLACEHOLDER",
    "http": {
        "total_requests": 1000,
        "rps": 100.0,
        "latency": {"avg": 10.0, "p50": 9.0, "p95": 18.0, "p99": 25.0},
    },
    "mcp": {"total_mcp_requests": 800},
    "tools": {},
}


def make_results_dir(servers):
    """
    Create a temp results directory with subdirs for each server name.
    If the server name maps to True, write a minimal k6.json; if False, skip it.
    Returns the temp directory path.
    """
    tmpdir = tempfile.mkdtemp(prefix="bench_test_")
    for name, with_k6 in servers.items():
        server_dir = os.path.join(tmpdir, name)
        os.makedirs(server_dir)
        if with_k6:
            data = dict(MINIMAL_K6)
            data["server"] = name
            with open(os.path.join(server_dir, "k6.json"), "w") as f:
                json.dump(data, f)
    return tmpdir


PASS = 0
FAIL = 0


def run(name, fn):
    global PASS, FAIL
    try:
        fn()
        print(f"  [PASS] {name}")
        PASS += 1
    except AssertionError as e:
        print(f"  [FAIL] {name}: {e}")
        FAIL += 1


# ─── Test cases ───────────────────────────────────────────────────────────────


def test_subset_selection():
    """get_servers returns only the servers whose k6.json is present."""
    tmpdir = make_results_dir({"java-vt": True, "java-vt-native": True})
    try:
        servers = get_servers(tmpdir)
        assert servers == ["java-vt", "java-vt-native"], (
            f"Expected ['java-vt', 'java-vt-native'], got {servers}"
        )
        summary = consolidate(tmpdir)
        assert list(summary["servers"].keys()) == ["java-vt", "java-vt-native"], (
            f"consolidate() returned unexpected keys: {list(summary['servers'].keys())}"
        )
    finally:
        shutil.rmtree(tmpdir)


def test_canonical_order():
    """Servers appear in canonical order regardless of directory creation order."""
    # Create dirs in non-canonical order
    tmpdir = make_results_dir({"nodejs": True, "python": True, "go": True})
    try:
        servers = get_servers(tmpdir)
        assert servers == ["python", "go", "nodejs"], (
            f"Expected canonical order [python, go, nodejs], got {servers}"
        )
    finally:
        shutil.rmtree(tmpdir)


def test_unknown_server_at_end():
    """Unknown servers appear after all canonical ones, sorted alphabetically."""
    tmpdir = make_results_dir({"rust-server": True, "python": True})
    try:
        servers = get_servers(tmpdir)
        assert servers[0] == "python", (
            f"Expected 'python' first, got {servers}"
        )
        assert "rust-server" in servers, "Expected 'rust-server' to be present"
        # rust-server must come after the canonical entry
        assert servers.index("python") < servers.index("rust-server"), (
            "Canonical 'python' should precede unknown 'rust-server'"
        )
    finally:
        shutil.rmtree(tmpdir)


def test_missing_k6_json_ignored():
    """Server directory without k6.json is silently ignored."""
    tmpdir = make_results_dir({"python": True, "go": False})
    try:
        servers = get_servers(tmpdir)
        assert "go" not in servers, (
            f"'go' has no k6.json and should be excluded, but got {servers}"
        )
        assert "python" in servers, "python should still appear"
        summary = consolidate(tmpdir)
        assert "go" not in summary["servers"], (
            "'go' without k6.json must not appear in summary"
        )
    finally:
        shutil.rmtree(tmpdir)


def test_empty_directory():
    """get_servers on an empty directory returns an empty list."""
    tmpdir = tempfile.mkdtemp(prefix="bench_test_empty_")
    try:
        servers = get_servers(tmpdir)
        assert servers == [], f"Expected [], got {servers}"
    finally:
        shutil.rmtree(tmpdir)


# ─── Runner ───────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("\nconsolidate.py — unit tests")
    print("=" * 40)
    run("Subset selection (java-vt + java-vt-native)", test_subset_selection)
    run("Canonical order (nodejs,python,go → python,go,nodejs)", test_canonical_order)
    run("Unknown server goes to end", test_unknown_server_at_end)
    run("Missing k6.json is ignored", test_missing_k6_json_ignored)
    run("Empty directory → get_servers() == []", test_empty_directory)
    print("=" * 40)
    print(f"Results: {PASS} passed, {FAIL} failed")
    sys.exit(0 if FAIL == 0 else 1)

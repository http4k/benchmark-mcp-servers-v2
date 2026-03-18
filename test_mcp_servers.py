#!/usr/bin/env python3
"""
Test script for all MCP servers (benchmark v3 — I/O tools).
Tests: initialize, tools/list, and tools/call for each of the 3 I/O tools.
Handles both plain JSON and SSE (text/event-stream) responses.
Requires: mcp-api-service (port 8100) and mcp-redis (port 6379) to be running and seeded.
"""

import json
import requests
from datetime import datetime

# All servers use the same endpoint path
SERVERS = {
    "python":  "http://localhost:8082/mcp",
    "go":      "http://localhost:8081/mcp",
    "nodejs":  "http://localhost:8083/mcp",
    "java":    "http://localhost:8080/mcp",
    "java-native": "http://localhost:8084/mcp",
    "quarkus": "http://localhost:8085/mcp",
    "quarkus-native": "http://localhost:8086/mcp",
    "java-vt": "http://localhost:8087/mcp",
    "java-vt-native": "http://localhost:8088/mcp",
    "java-webflux": "http://localhost:8089/mcp",
    "java-webflux-native": "http://localhost:8090/mcp",
    "micronaut": "http://localhost:8091/mcp",
    "micronaut-native": "http://localhost:8092/mcp",
    "python-granian": "http://localhost:8093/mcp",
    "bun":            "http://localhost:8094/mcp",
    "rust":           "http://localhost:8095/mcp",
    "rust-axum":      "http://localhost:8096/mcp",
    "http4k":         "http://localhost:8097/mcp",
}


def mcp_request(url, method, params=None, request_id=1, session_id=None):
    """Send a JSON-RPC 2.0 request to an MCP server.
    Handles both plain JSON and SSE responses using streaming."""
    payload = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
    }
    if params:
        payload["params"] = params

    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id

    # Use stream=True to handle SSE responses that may keep connection open
    resp = requests.post(url, json=payload, headers=headers, timeout=10, stream=True)
    resp.raise_for_status()

    new_session_id = resp.headers.get("Mcp-Session-Id", session_id)
    content_type = resp.headers.get("Content-Type", "")

    if "text/event-stream" in content_type:
        # SSE: read line by line until we get a data: line with JSON
        for line in resp.iter_lines(decode_unicode=True):
            if line and line.startswith("data:"):
                payload_str = line[5:].strip()
                if payload_str:
                    resp.close()
                    return json.loads(payload_str), new_session_id
        resp.close()
        raise ValueError("No data found in SSE response")
    else:
        # Plain JSON
        result = resp.json()
        resp.close()
        return result, new_session_id


def mcp_notify(url, method, session_id=None):
    """Send a JSON-RPC 2.0 notification (no id, no response expected)."""
    payload = {"jsonrpc": "2.0", "method": method}
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    try:
        requests.post(url, json=payload, headers=headers, timeout=5, stream=True).close()
    except Exception:
        pass


def print_section(title):
    print(f"\n{'='*70}")
    print(f"  {title}")
    print("=" * 70)


def test_server(name, url):
    """Run full MCP test suite against a single server."""
    print_section(f"{name.upper()} ({url})")
    session_id = None

    try:
        # 1. Initialize
        print("\n1️⃣  initialize")
        result, session_id = mcp_request(url, "initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "test-client", "version": "1.0.0"},
        }, request_id=1)
        r = result.get("result", {})
        print(f"   ✅ protocol: {r.get('protocolVersion')}")
        print(f"   ✅ server:   {r.get('serverInfo', {}).get('name')}")

        # Send initialized notification
        mcp_notify(url, "notifications/initialized", session_id)

        # 2. tools/list
        print("\n2️⃣  tools/list")
        result, session_id = mcp_request(url, "tools/list", {}, request_id=2, session_id=session_id)
        tools = result.get("result", {}).get("tools", [])
        print(f"   ✅ {len(tools)} tools found:")
        for t in sorted(tools, key=lambda x: x["name"]):
            print(f"      • {t['name']}")

        # 3. tools/call — each tool
        print("\n3️⃣  tools/call")

        # 3a. search_products
        print("\n   📌 search_products(category=Electronics, min_price=50, max_price=500, limit=10)")
        result, session_id = mcp_request(url, "tools/call", {
            "name": "search_products",
            "arguments": {"category": "Electronics", "min_price": 50.0, "max_price": 500.0, "limit": 10},
        }, request_id=3, session_id=session_id)
        data = json.loads(result["result"]["content"][0]["text"])
        print(f"      total_found={data['total_found']}, products={len(data['products'])}, top10={len(data['top10_popular_ids'])}, server={data['server_type']}")
        assert data["total_found"] == 2251, f"Expected total_found=2251, got {data['total_found']}"
        assert len(data["products"]) == 10, f"Expected 10 products, got {len(data['products'])}"
        assert len(data["top10_popular_ids"]) == 10, f"Expected 10 popular ids, got {len(data['top10_popular_ids'])}"

        # 3b. get_user_cart
        print("\n   📌 get_user_cart(user_id=user-00042)")
        result, session_id = mcp_request(url, "tools/call", {
            "name": "get_user_cart",
            "arguments": {"user_id": "user-00042"},
        }, request_id=4, session_id=session_id)
        data = json.loads(result["result"]["content"][0]["text"])
        print(f"      user={data['user_id']}, cart_items={data['cart']['item_count']}, history={len(data['recent_history'])}, server={data['server_type']}")
        assert data["user_id"] == "user-00042", f"Expected user-00042, got {data['user_id']}"
        assert data["cart"]["item_count"] >= 1, f"Expected item_count>=1, got {data['cart']['item_count']}"
        assert len(data["recent_history"]) == 5, f"Expected 5 history entries, got {len(data['recent_history'])}"

        # 3c. checkout
        print("\n   📌 checkout(user_id=user-00042, items=[{product_id:42,quantity:2},{product_id:1337,quantity:1}])")
        result, session_id = mcp_request(url, "tools/call", {
            "name": "checkout",
            "arguments": {
                "user_id": "user-00042",
                "items": [{"product_id": 42, "quantity": 2}, {"product_id": 1337, "quantity": 1}],
            },
        }, request_id=5, session_id=session_id)
        data = json.loads(result["result"]["content"][0]["text"])
        print(f"      order={data['order_id']}, total={data['total']}, items={data['items_count']}, status={data['status']}, server={data['server_type']}")
        assert data["status"] == "confirmed", f"Expected confirmed, got {data['status']}"
        assert data["total"] > 0, f"Expected total>0, got {data['total']}"
        assert data["items_count"] == 2, f"Expected items_count=2, got {data['items_count']}"

        print(f"\n   ✅ ALL PASSED for {name.upper()}")
        return True

    except requests.exceptions.ConnectionError:
        print(f"   ❌ CONNECTION ERROR — is {name} running?")
        return False
    except Exception as e:
        print(f"   ❌ {type(e).__name__}: {e}")
        return False


def main():
    print_section("MCP SERVERS — COMPREHENSIVE TEST")
    print(f"  Timestamp: {datetime.now().isoformat()}")

    results = {}
    for name, url in SERVERS.items():
        results[name] = test_server(name, url)

    # Summary
    print_section("SUMMARY")
    for name, ok in results.items():
        print(f"  {'✅' if ok else '❌'} {name}")

    passed = sum(results.values())
    total = len(results)
    print(f"\n  {passed}/{total} servers passed")

    if passed == total:
        print("\n  🎉 ALL SERVERS PASSED!")
    print("=" * 70)


if __name__ == "__main__":
    main()

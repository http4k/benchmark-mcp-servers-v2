#!/usr/bin/env python3
"""
Seed Redis with benchmark data for MCP v3 I/O benchmark.

Keys seeded:
  bench:cart:user-{N:05d}     HASH   10,000   carts
  bench:history:user-{N:05d}  LIST   1,000    order histories (20 entries each)
  bench:popular               ZSET   1        100k members (product:{id} → score)
  bench:ratelimit:user-{N:05d} STRING 100      rate limit counters (start at 0)
"""

import json
import os
import sys

import redis

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))

print(f"Connecting to Redis at {REDIS_HOST}:{REDIS_PORT}...", flush=True)
r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
r.ping()
print("Connected.", flush=True)

# ── 1. Seed 10,000 carts ──────────────────────────────────────────────────────
print("Seeding 10k carts (bench:cart:user-{N:05d})...", flush=True)
pipe = r.pipeline(transaction=False)
for n in range(1, 10_001):
    key = f"bench:cart:user-{n:05d}"
    # 2 or 3 items per cart, deterministic
    items = [
        {"product_id": (n * 7) % 100_000 + 1, "qty": 1 + n % 3},
        {"product_id": (n * 13) % 100_000 + 1, "qty": 1 + n % 2},
    ]
    if n % 3 == 0:
        items.append({"product_id": (n * 17) % 100_000 + 1, "qty": 1})
    items_json = json.dumps(items)
    # Rough total (not pricing-exact, just non-zero)
    total = sum(it["qty"] * (((it["product_id"] * 100) % 9_999) + 1) / 100.0 for it in items)
    pipe.hset(key, mapping={
        "user_id": f"user-{n:05d}",
        "items": items_json,
        "created": "1740000000",
        "expires": "1740086400",
        "total": f"{total:.2f}",
    })
    if n % 1_000 == 0:
        pipe.execute()
        print(f"  {n}/10000 carts...", flush=True)
        pipe = r.pipeline(transaction=False)
pipe.execute()
print("Carts done.", flush=True)

# ── 2. Seed 1,000 order histories (20 entries each) ───────────────────────────
print("Seeding 1k histories (bench:history:user-{N:05d})...", flush=True)
pipe = r.pipeline(transaction=False)
for n in range(1, 1_001):
    key = f"bench:history:user-{n:05d}"
    pipe.delete(key)
    for j in range(1, 21):
        entry = json.dumps({
            "order_id": f"ORD-{n:05d}-{j:02d}",
            "product_id": (n * j * 7) % 100_000 + 1,
            "qty": 1 + j % 3,
            "price": round(((n * j * 13) % 9_999 + 1) / 100.0, 2),
            "ts": 1_740_000_000 + n * 86_400 + j * 3_600,
        })
        pipe.rpush(key, entry)
    if n % 100 == 0:
        pipe.execute()
        print(f"  {n}/1000 histories...", flush=True)
        pipe = r.pipeline(transaction=False)
pipe.execute()
print("Histories done.", flush=True)

# ── 3. Seed bench:popular ZSET (100k members) ─────────────────────────────────
print("Seeding bench:popular ZSET (100k members)...", flush=True)
pipe = r.pipeline(transaction=False)
for i in range(1, 100_001):
    score = i * 7 % 10_000
    pipe.zadd("bench:popular", {f"product:{i}": score})
    if i % 5_000 == 0:
        pipe.execute()
        print(f"  {i}/100000 ZADD entries...", flush=True)
        pipe = r.pipeline(transaction=False)
pipe.execute()
print("Popular ZSET done.", flush=True)

# ── 4. Seed 100 rate-limit counters ───────────────────────────────────────────
print("Seeding 100 rate-limit counters (bench:ratelimit:user-{N:05d})...", flush=True)
pipe = r.pipeline(transaction=False)
for n in range(1, 101):
    pipe.set(f"bench:ratelimit:user-{n:05d}", 0)
pipe.execute()
print("Rate limiters done.", flush=True)

print("Redis seeding complete!", flush=True)

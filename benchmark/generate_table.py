import json
import os

folders = [
    "results/20260225_155402",
    "results/20260225_173220"
]

metrics = {}
for folder in folders:
    path = os.path.join("/home/thiago/Dev/Git/benchmark-mcp-servers/benchmark", folder, "summary.json")
    with open(path, "r") as f:
        data = json.load(f)
        for srv, srv_data in data.get("servers", {}).items():
            if srv not in metrics:
                metrics[srv] = {"rps": [], "lat": [], "mem": []}
            metrics[srv]["rps"].append(srv_data.get("http", {}).get("rps", 0))
            metrics[srv]["lat"].append(srv_data.get("http", {}).get("latency", {}).get("p95", 0))
            metrics[srv]["mem"].append(srv_data.get("resources", {}).get("memory_mb", {}).get("max", 0))

averages = []
for srv, data in metrics.items():
    avg_rps = sum(data["rps"]) / len(data["rps"])
    avg_lat = sum(data["lat"]) / len(data["lat"])
    avg_mem = sum(data["mem"]) / len(data["mem"])
    averages.append({
        "server": srv,
        "rps": avg_rps,
        "lat": avg_lat,
        "mem": avg_mem
    })

# Sort by RPS descending
averages.sort(key=lambda x: x["rps"], reverse=True)

print("### 🚀 Throughput Máximo (Requisições por Segundo)")
print("| Posição | Servidor | Média de RPS | Latência P95 (ms) | Pico de Memória (MB) |")
print("|---|---|---|---|---|")
for i, item in enumerate(averages, 1):
    medal = "🥇" if i == 1 else "🥈" if i == 2 else "🥉" if i == 3 else f"{i}º"
    print(f"| {medal} | `{item['server']}` | **{item['rps']:.1f}** | {item['lat']:.1f} | {item['mem']:.1f} |")

# Sort by Memory ascending
averages_mem = sorted(averages, key=lambda x: x["mem"])

print("\n### 💾 Eficiência de Memória (Pico)")
print("| Posição | Servidor | Pico de Memória (MB) | Média de RPS |")
print("|---|---|---|---|")
for i, item in enumerate(averages_mem, 1):
    medal = "🥇" if i == 1 else "🥈" if i == 2 else "🥉" if i == 3 else f"{i}º"
    print(f"| {medal} | `{item['server']}` | **{item['mem']:.1f}** | {item['rps']:.1f} |")

# Sort by Latency ascending
averages_lat = sorted(averages, key=lambda x: x["lat"])

print("\n### ⚡ Menor Latência (P95)")
print("| Posição | Servidor | Latência P95 (ms) | Média de RPS |")
print("|---|---|---|---|")
for i, item in enumerate(averages_lat, 1):
    medal = "🥇" if i == 1 else "🥈" if i == 2 else "🥉" if i == 3 else f"{i}º"
    print(f"| {medal} | `{item['server']}` | **{item['lat']:.1f}** | {item['rps']:.1f} |")

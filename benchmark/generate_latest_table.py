import json
import os

folders = [
    "results/20260226_173129",
    "results/20260226_185707",
    "results/20260226_202249"
]

print("## 1. Analise Isolada de Execucoes (Ultimas 3)")

metrics = {}
for folder in folders:
    path = os.path.join("/home/thiago/Dev/Git/benchmark-mcp-servers/benchmark", folder, "summary.json")
    if not os.path.exists(path):
        continue
    
    run_time = folder.split('_')[-1][:4]
    run_time_fmt = f"{run_time[:2]}:{run_time[2:]}"
    print(f"\n### Execucao {run_time_fmt}")
        
    with open(path, "r") as f:
        data = json.load(f)
        
        run_data = []
        for srv, srv_data in data.get("servers", {}).items():
            if srv not in metrics:
                metrics[srv] = {"rps": [], "lat": [], "mem": [], "err": []}
                
            rps = srv_data.get("http", {}).get("rps", 0)
            lat = srv_data.get("http", {}).get("latency", {}).get("p95", 0)
            mem = srv_data.get("resources", {}).get("memory_mb", {}).get("max", 0)
            err = srv_data.get("mcp", {}).get("error_rate", 0)
            
            metrics[srv]["rps"].append(rps)
            metrics[srv]["lat"].append(lat)
            metrics[srv]["mem"].append(mem)
            metrics[srv]["err"].append(err)
            
            run_data.append({"srv": srv, "rps": rps, "lat": lat, "mem": mem, "err": err})
            
        run_data.sort(key=lambda x: x["rps"], reverse=True)
        print("| Servidor | RPS | Lat P95 (ms) | Memoria Max (MB) | Erros |")
        print("|---|---|---|---|---|")
        for d in run_data:
            print(f"| {d['srv']} | {d['rps']:.1f} | {d['lat']:.1f} | {d['mem']:.1f} | {d['err']:.2f}% |")

print("\n\n## 2. Visao Consolidada Media (Ultimas 3 Execucoes)")

averages = []
for srv, data in metrics.items():
    if not data["rps"]: continue
    avg_rps = sum(data["rps"]) / len(data["rps"])
    avg_lat = sum(data["lat"]) / len(data["lat"])
    avg_mem = sum(data["mem"]) / len(data["mem"])
    err_sum = sum(data["err"])
    averages.append({
        "server": srv,
        "rps": avg_rps,
        "lat": avg_lat,
        "mem": avg_mem,
        "err_sum": err_sum
    })

averages.sort(key=lambda x: x["rps"], reverse=True)

print("| Posição | Servidor | Média de RPS | Latência P95 (ms) | Pico de Memória (MB) |")
print("|---|---|---|---|---|")
for i, item in enumerate(averages, 1):
    medal = "🥇" if i == 1 else "🥈" if i == 2 else "🥉" if i == 3 else f"{i}º"
    print(f"| {medal} | `{item['server']}` | **{item['rps']:.1f}** | {item['lat']:.1f} | {item['mem']:.1f} |")

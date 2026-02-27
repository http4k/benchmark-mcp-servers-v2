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
                metrics[srv] = {"rps": [], "lat_avg": [], "lat_p50": [], "lat_p90": [], "lat_p95": [], "lat_p99": [], "err": [], "mem_avg": [], "cpu_avg": []}
                
            rps = srv_data.get("http", {}).get("rps", 0)
            lat_avg = srv_data.get("http", {}).get("latency", {}).get("avg", 0)
            lat_p50 = srv_data.get("http", {}).get("latency", {}).get("p50", 0)
            lat_p90 = srv_data.get("http", {}).get("latency", {}).get("p90", 0)
            lat_p95 = srv_data.get("http", {}).get("latency", {}).get("p95", 0)
            lat_p99 = srv_data.get("http", {}).get("latency", {}).get("p99", 0)
            err = srv_data.get("mcp", {}).get("error_rate", 0)
            mem_avg = srv_data.get("resources", {}).get("memory_mb", {}).get("avg", 0)
            cpu_avg = srv_data.get("resources", {}).get("cpu", {}).get("avg", 0)
            
            metrics[srv]["rps"].append(rps)
            metrics[srv]["lat_avg"].append(lat_avg)
            metrics[srv]["lat_p50"].append(lat_p50)
            metrics[srv]["lat_p90"].append(lat_p90)
            metrics[srv]["lat_p95"].append(lat_p95)
            metrics[srv]["lat_p99"].append(lat_p99)
            metrics[srv]["err"].append(err)
            metrics[srv]["mem_avg"].append(mem_avg)
            metrics[srv]["cpu_avg"].append(cpu_avg)
            
            run_data.append({"srv": srv, "rps": rps, "err": err, "lat_avg": lat_avg, "lat_p50": lat_p50, "lat_p90": lat_p90, "lat_p95": lat_p95, "lat_p99": lat_p99, "mem_avg": mem_avg, "cpu_avg": cpu_avg})
            
        run_data.sort(key=lambda x: x["rps"], reverse=True)
        # Reduced spacing and header names
        print("|Servidor|RPS|Err|AVG|P50|P90|P95|P99|Mem|CPU|")
        print("|---|---|---|---|---|---|---|---|---|---|")
        for d in run_data:
            print(f"|{d['srv']}|{d['rps']:.0f}|{d['err']:.0f}%|{d['lat_avg']:.1f}|{d['lat_p50']:.1f}|{d['lat_p90']:.1f}|{d['lat_p95']:.1f}|{d['lat_p99']:.1f}|{d['mem_avg']:.0f}M|{d['cpu_avg']:.0f}%|")

print("\n\n## 2. Visão Consolidada Média (Últimas 3 Execuções)")

averages = []
for srv, data in metrics.items():
    if not data["rps"]: continue
    avg_rps = sum(data["rps"]) / len(data["rps"])
    avg_err = sum(data["err"]) / len(data["err"])
    avg_lat_avg = sum(data["lat_avg"]) / len(data["lat_avg"])
    avg_lat_p50 = sum(data["lat_p50"]) / len(data["lat_p50"])
    avg_lat_p90 = sum(data["lat_p90"]) / len(data["lat_p90"])
    avg_lat_p95 = sum(data["lat_p95"]) / len(data["lat_p95"])
    avg_lat_p99 = sum(data["lat_p99"]) / len(data["lat_p99"])
    avg_mem_avg = sum(data["mem_avg"]) / len(data["mem_avg"])
    avg_cpu_avg = sum(data["cpu_avg"]) / len(data["cpu_avg"])
    
    averages.append({
        "server": srv,
        "rps": avg_rps,
        "err": avg_err,
        "lat_avg": avg_lat_avg,
        "lat_p50": avg_lat_p50,
        "lat_p90": avg_lat_p90,
        "lat_p95": avg_lat_p95,
        "lat_p99": avg_lat_p99,
        "mem": avg_mem_avg,
        "cpu": avg_cpu_avg
    })

averages.sort(key=lambda x: x["rps"], reverse=True)

# Slimmer format for horizontal spacial limits. No medals.
print("|Srv|RPS|Er|AVG|P50|P90|P95|P99|Mem|CPU|")
print("|---|---|---|---|---|---|---|---|---|---|")
for i, item in enumerate(averages, 1):
    print(f"|{item['server']}|{item['rps']:.0f}|{item['err']:.0f}%|{item['lat_avg']:.1f}|{item['lat_p50']:.1f}|{item['lat_p90']:.1f}|{item['lat_p95']:.1f}|{item['lat_p99']:.1f}|{item['mem']:.0f}M|{item['cpu']:.0f}%|")

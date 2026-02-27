import json
import os

folders = [
    "results/20260225_114505",
    "results/20260225_131441",
    "results/20260225_155402",
    "results/20260225_173220"
]

metrics = {}
for folder in folders:
    path = os.path.join("/home/thiago/Dev/Git/benchmark-mcp-servers/benchmark", folder, "summary.json")
    if not os.path.exists(path):
        print(f"File not found: {path}")
        continue
    with open(path, "r") as f:
        data = json.load(f)
        for srv, srv_data in data.get("servers", {}).items():
            if srv not in metrics:
                metrics[srv] = []
            rps = srv_data.get("http", {}).get("rps", 0)
            lat95 = srv_data.get("http", {}).get("latency", {}).get("p95", 0)
            mem = srv_data.get("resources", {}).get("memory_mb", {}).get("max", 0)
            err = srv_data.get("mcp", {}).get("error_rate", 0)
            metrics[srv].append((folder.split('_')[-1], rps, lat95, mem, err))

run_ids = [f.split('_')[-1] for f in folders]

print("## Consolidation per Server\n")
for srv, runs in metrics.items():
    print(f"### {srv}")
    print("Metric | " + " | ".join(run_ids))
    print("--- | " + " | ".join(["---"] * len(folders)))
    rps_str = " | ".join([f"{r:.1f}" for _, r, _, _, _ in runs])
    print(f"RPS | {rps_str}")
    lat_str = " | ".join([f"{l:.1f}" for _, _, l, _, _ in runs])
    print(f"Lat p95 (ms) | {lat_str}")
    mem_str = " | ".join([f"{m:.1f}" for _, _, _, m, _ in runs])
    print(f"Mem Max (MB) | {mem_str}")
    err_str = " | ".join([f"{e:.2f}%" for _, _, _, _, e in runs])
    print(f"Error Rate | {err_str}")
    print()

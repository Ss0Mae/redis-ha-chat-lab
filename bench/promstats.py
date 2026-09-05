"""실행 구간의 자원 지표를 Prometheus 에서 뽑는다. 사용: python3 bench/promstats.py <start_epoch> <end_epoch>  → JSON"""
import json, sys, urllib.request, urllib.parse
start, end = float(sys.argv[1]), float(sys.argv[2])
PROM = "http://localhost:9093"
def q(expr):
    u = f"{PROM}/api/v1/query_range?" + urllib.parse.urlencode({"query": expr, "start": start, "end": end, "step": 1})
    try: return json.load(urllib.request.urlopen(u, timeout=10))["data"]["result"]
    except Exception: return []
def series_stats(res, label=None):
    out = {}
    for s in res:
        vals = [float(v[1]) for v in s["values"] if v[1] not in ("NaN", "+Inf", "-Inf")]
        if not vals: continue
        key = s["metric"].get(label, "app") if label else "app"
        out[key] = {"avg": sum(vals) / len(vals), "max": max(vals), "min": min(vals), "last": vals[-1]}
    return out
r = {
    "app_cpu": series_stats(q("process_cpu_usage")),
    "system_cpu": series_stats(q("system_cpu_usage")),
    "app_heap_mb": {k: {kk: vv / 1048576 for kk, vv in v.items()} for k, v in series_stats(q('sum(jvm_memory_used_bytes{area="heap"})')).items()},
    "redis_mem_mb": {k: {kk: vv / 1048576 for kk, vv in v.items()} for k, v in series_stats(q("redis_memory_used_bytes"), "node").items()},
    "redis_clients": series_stats(q("redis_connected_clients"), "node"),
    "redis_net_in_kbps": {k: {kk: vv / 1024 for kk, vv in v.items()} for k, v in series_stats(q("rate(redis_net_input_bytes_total[5s])"), "node").items()},
    "redis_net_out_kbps": {k: {kk: vv / 1024 for kk, vv in v.items()} for k, v in series_stats(q("rate(redis_net_output_bytes_total[5s])"), "node").items()},
    "app_connections_created": series_stats(q("increase(redis_connection_events_total{type=\"connected\"}[1h])")),
}
print(json.dumps(r))

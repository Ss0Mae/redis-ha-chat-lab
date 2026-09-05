"""실험 1건의 raw 파일(experiment.json, samples.json, k6.json) → runs.jsonl 한 줄.
사용: python3 bench/collect.py <set> <experimentId> <scenario> <rps> <rep> <driver>"""
import json, sys, os, statistics

set_, eid, scenario, rps, rep, driver = sys.argv[1:7]
raw = f"results/{set_}/raw/{eid}"
exp = json.load(open(raw + ".experiment.json"))
samples = json.load(open(raw + ".samples.json")) if os.path.exists(raw + ".samples.json") else []
summary = exp.get("summary", {})
t = summary.get("timings", {})
c = summary.get("consistency", {})
phases = summary.get("phases", {})

def phase_stats(name):
    rows = [r for r in samples if r["phase"] == name]
    if not rows: return None
    ok = sum(r["ok"] for r in rows); fail = sum(r["fail"] for r in rows)
    errs = {}
    for r in rows:
        be = r.get("by_error")
        if isinstance(be, str):
            try: be = json.loads(be)
            except Exception: be = {}
        for k, v in (be or {}).items(): errs[k] = errs.get(k, 0) + v
    p95 = sorted(r["p95_us"] for r in rows); p99 = sorted(r["p99_us"] for r in rows)
    zero_secs = sum(1 for r in rows if r["ok"] == 0 and r["fail"] > 0)
    return {"seconds": len(rows), "ok": ok, "fail": fail, "error_rate_pct": round(fail * 100 / (ok + fail), 2) if ok + fail else 0,
            "tps": round((ok + fail) / len(rows), 1), "success_tps": round(ok / len(rows), 1),
            "p95_ms_median": p95[len(p95) // 2] / 1000, "p99_ms_median": p99[len(p99) // 2] / 1000, "p95_ms_max": p95[-1] / 1000,
            "zero_success_seconds": zero_secs, "errors": errs}

k6 = None
if os.path.exists(raw + ".k6.json"):
    kd = json.load(open(raw + ".k6.json"))["metrics"]
    def m(name, field):
        v = kd.get(name, {}).get("values", {})
        return v.get(field)
    k6 = {}
    for ph in ["before", "during", "after"]:
        d = kd.get(f"http_req_duration{{phase:{ph}}}", {}).get("values", {})
        reqs = m(f"http_reqs{{phase:{ph}}}", "count")
        outs = {o: m(f"outcomes{{phase:{ph},outcome:{o}}}", "count") for o in ["OK", "TIMEOUT", "CONNECTION", "CLUSTERDOWN", "MOVED", "ASK", "READONLY", "NOREPLICAS", "OTHER", "HTTP_ERR"]}
        outs = {k: v for k, v in outs.items() if v}
        # k6 의 rate 는 전체 테스트 시간으로 나눈 값이라 단계별 초 수(metric_sample 행 수)로 다시 계산한다
        secs = sum(1 for r_ in samples if r_.get("phase") == {"before": "BEFORE", "during": "DURING", "after": "AFTER"}[ph]) or None
        k6[ph] = {"requests": reqs, "seconds": secs, "rps": (round(reqs / secs, 1) if reqs and secs else m(f"http_reqs{{phase:{ph}}}", "rate")), "avg_ms": d.get("avg"), "p50_ms": d.get("p(50)"), "p95_ms": d.get("p(95)"), "p99_ms": d.get("p(99)"), "max_ms": d.get("max"), "outcomes": outs,
                  "error_rate_pct": round((1 - (outs.get("OK", 0) / reqs)) * 100, 2) if reqs else None}

# 서버 로그 시각이 있으면 T1~T3 를 로그 기준으로 보정한다(폴링 200 ms 오차 제거). Sentinel 은 pubsub 이 이미 즉시 전달되므로 그대로.
lt = summary.get("log_timings", {})
def at(key):
    v = lt.get(key)
    if not v: return None
    import datetime, re
    s_ = re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], v["at"].replace("Z", "+00:00"))
    return datetime.datetime.fromisoformat(s_)
def ms(a, b): return None if a is None or b is None else round((b - a).total_seconds() * 1000)
t0 = at_t0 = None
if t.get("t0_inject"):
    import datetime, re
    at_t0 = datetime.datetime.fromisoformat(re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], t["t0_inject"].replace("Z", "+00:00")))
log_derived = {}
if exp.get("topology") == "cluster" and at_t0 is not None:
    if at("t1_log_fail_quorum"): log_derived["detect_ms"] = ms(at_t0, at("t1_log_fail_quorum"))
    # T2 = Replica 가 Failover 절차를 시작(선거 지연 대기 시작), T3 = 선거 승리. 선거 자체(요청→승리)는 election_only_ms 로 따로 둔다.
    if at("t2_log_election_delayed") and at("t3_log_election_won"): log_derived["promote_ms"] = ms(at("t2_log_election_delayed"), at("t3_log_election_won"))
    elif at("t2_log_election_start") and at("t3_log_election_won"): log_derived["promote_ms"] = ms(at("t2_log_election_start"), at("t3_log_election_won"))
    if at("t2_log_election_start") and at("t3_log_election_won"): log_derived["election_only_ms"] = ms(at("t2_log_election_start"), at("t3_log_election_won"))
    if at("t3_log_election_won") and t.get("t5_first_write_ok"):
        t5 = datetime.datetime.fromisoformat(re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], t["t5_first_write_ok"].replace("Z", "+00:00")))
        log_derived["client_reconnect_ms"] = ms(at("t3_log_election_won"), t5)
timings = {k: t.get(k) for k in ["detect_ms", "promote_ms", "client_aware_ms", "client_reconnect_ms", "outage_ms", "stabilize_ms", "rejoin_ms"]}
timings_watcher = dict(timings)
timings.update({k: v for k, v in log_derived.items() if k in timings})
rejoin_type = "full" if lt.get("rejoin_log_full_resync") or lt.get("rejoin_log_partial_rejected") else ("partial" if lt.get("rejoin_log_partial_resync") else None)

resources = {}
if os.path.exists(raw + ".prom.json"):
    try: resources["prom"] = json.load(open(raw + ".prom.json"))
    except Exception: pass
if os.path.exists(raw + ".stats.jsonl"):
    cpu, mem, netin, netout = {}, {}, {}, {}
    def num(s, unit_table):
        s = s.strip()
        for u, f in unit_table:
            if s.endswith(u): return float(s[:-len(u)]) * f
        try: return float(s)
        except ValueError: return 0.0
    UNITS = [("GiB", 1024), ("MiB", 1), ("kiB", 1 / 1024), ("KiB", 1 / 1024), ("GB", 1000), ("MB", 1), ("kB", 0.001), ("B", 1 / 1048576)]
    for line in open(raw + ".stats.jsonl"):
        try: d = json.loads(line)
        except Exception: continue
        n = d.get("Name"); 
        if not n: continue
        cpu.setdefault(n, []).append(float(d.get("CPUPerc", "0%").rstrip("%") or 0))
        mem.setdefault(n, []).append(num(d.get("MemUsage", "0B").split("/")[0], UNITS))
        io = d.get("NetIO", "0B / 0B").split("/")
        netin.setdefault(n, []).append(num(io[0], UNITS)); netout.setdefault(n, []).append(num(io[1], UNITS) if len(io) > 1 else 0)
    resources["docker"] = {n: {"cpu_avg_pct": round(sum(v) / len(v), 1), "cpu_max_pct": round(max(v), 1), "mem_max_mb": round(max(mem[n]), 1),
                              "net_in_mb": round(max(netin[n]) - min(netin[n]), 2), "net_out_mb": round(max(netout[n]) - min(netout[n]), 2)} for n, v in cpu.items() if v}

# 쓰기 공백(write gap): 장애 shard 를 향한 쓰기가 "처음 실패한 시각" 부터 "그 뒤 첫 성공" 까지. 파티션처럼 장애 주입 직후에는
# 멀쩡하다가 나중에 실패가 시작되는 시나리오에서 T5−T0 만으로는 중단을 잘못 잰다. BATCH 기록(request_log)이 있을 때 계산한다.
gap = {}
if at_t0 is not None and exp.get("recordingMode") == "BATCH":
    import subprocess, datetime as _dt
    kst = _dt.timezone(_dt.timedelta(hours=9))
    t0k = at_t0.astimezone(kst).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    shard = exp.get("failedShard") or 0
    sql = (f"SELECT status, requested_at, acked_at FROM request_log WHERE experiment_id='{eid}' AND op IN ('SET','INCR','SEND') AND shard={shard} "
           f"AND requested_at >= '{t0k}' ORDER BY requested_at")
    try:
        out = subprocess.run(["docker", "exec", "lab-mysql", "mysql", "-ulab", "-plab", "lab", "-N", "-B", "-e", sql], capture_output=True, text=True, timeout=60).stdout
        first_fail = None; t5b = None; last_fail = None
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) < 3: continue
            st, req, ack = parts
            reqt = _dt.datetime.strptime(req[:23], "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=kst) if "." in req else _dt.datetime.strptime(req[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=kst)
            if st != "OK":
                if first_fail is None: first_fail = reqt
                last_fail = reqt
            elif first_fail is not None and t5b is None and ack != "NULL":
                t5b = _dt.datetime.strptime(ack[:23], "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=kst) if "." in ack else _dt.datetime.strptime(ack[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=kst)
        if first_fail is not None:
            gap["first_fail_ms"] = ms(at_t0, first_fail)
            if t5b is not None:
                gap["t5_after_fail"] = t5b.astimezone(_dt.timezone.utc).isoformat()
                gap["write_gap_ms"] = ms(first_fail, t5b)
                gap["outage_ms"] = ms(at_t0, t5b)
        else:
            gap["no_write_failure"] = True
    except Exception as ex:
        gap["error"] = str(ex)[:120]
timings["outage_ms_app"] = timings.get("outage_ms")
if gap.get("t5_after_fail") and t.get("t3_promoted"):
    import datetime as _dt2, re as _re2
    _t3 = _dt2.datetime.fromisoformat(_re2.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], t["t3_promoted"].replace("Z", "+00:00")))
    _t3l = at("t3_log_election_won") or _t3
    timings["client_reconnect_ms"] = ms(_t3l if exp.get("topology") == "cluster" else _t3, _dt2.datetime.fromisoformat(gap["t5_after_fail"]))
if "outage_ms" in gap: timings["outage_ms"] = gap["outage_ms"]
elif gap.get("no_write_failure"): timings["outage_ms"] = None
timings["first_fail_ms"] = gap.get("first_fail_ms"); timings["write_gap_ms"] = gap.get("write_gap_ms")

row = {"set": set_, "id": eid, "name": exp.get("name"), "topology": exp.get("topology"), "scenario": scenario, "rps": int(rps), "rep": rep, "driver": driver,
       "failedNode": exp.get("failedNode"), "failedShard": exp.get("failedShard"), "redisSettings": exp.get("redisSettings"), "fault": exp.get("fault"),
       "timings": timings, "timings_watcher": timings_watcher, "log_derived": log_derived, "rejoin_resync": rejoin_type,
       "t": {k: t.get(k) for k in ["t0_inject", "t1_detected", "t2_promotion_start", "t3_promoted", "t3b_switch_master", "t4_client_aware", "t5_first_write_ok", "t6_stable", "recoveredAt", "recoveredStableAt"]},
       "consistency": c, "write_gap": gap, "resources": resources, "log_timings": summary.get("log_timings", {}), "phases": {p: phase_stats(p) for p in ["BEFORE", "DURING", "AFTER"]}, "k6": k6,
       "events": [(e["at"], e["source"], e["type"]) for e in exp.get("events", []) if e["source"] != "lettuce" or e["type"] in ("ConnectedEvent", "ClusterTopologyChangedEvent")][:60]}
print(json.dumps(row, ensure_ascii=False))

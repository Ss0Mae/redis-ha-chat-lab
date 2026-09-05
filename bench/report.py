"""results/*/runs.jsonl → docs/results.md + docs/charts/*.png + results/summary.json
모든 수치는 실측값이며 반복 측정의 중앙값과 [최소–최대] 를 함께 쓴다. 실행: .venv/bin/python bench/report.py"""
import glob, json, os, statistics, datetime
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import warnings
warnings.filterwarnings("ignore")
plt.rcParams["font.family"] = "Apple SD Gothic Neo"
plt.rcParams["axes.unicode_minus"] = False
plt.rcParams["figure.dpi"] = 130

COLOR = {"sentinel": "#2563eb", "cluster": "#0f9d58"}
TOPOS = ["sentinel", "cluster"]
LABEL = {"sentinel": "Sentinel", "cluster": "Cluster"}
SC_LABEL = {
    "KILL_PRIMARY": "Primary 강제 종료(SIGKILL)", "STOP_PRIMARY": "Primary 정상 종료(SIGTERM)", "PAUSE_PRIMARY": "Primary 프로세스 정지(pause)",
    "PARTITION_PRIMARY_FROM_SENTINELS": "Primary↔Sentinel 단절", "PARTITION_PRIMARY_FROM_REPLICAS": "Primary↔Replica 복제 단절",
    "PARTITION_PRIMARY_FROM_APP": "앱↔Primary 만 단절", "PARTITION_PRIMARY_FROM_PEERS": "Primary↔다른 Cluster 노드 단절",
    "NETEM_GLOBAL_DELAY": "전체 네트워크 지연 200 ms", "NETEM_LOSS": "패킷 손실 5 %", "KILL_REPLICA_THEN_PRIMARY": "Replica 1대 선행 장애 후 Primary 종료",
    "KILL_ALL_REPLICAS_THEN_PRIMARY": "Replica 전부 선행 장애 후 Primary 종료", "STOP_SENTINELS_THEN_KILL_PRIMARY": "Sentinel 2/3 중지(quorum 부족) 후 Primary 종료",
    "KILL_TWO_PRIMARIES": "Primary 2/3 종료(과반 상실)", "STOP_ALL_SENTINELS": "Sentinel 전부 중지(Redis 정상)",
    "NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY": "복제 지연 500 ms 상태에서 Primary 종료", "STOP_PRIMARY_AND_ITS_REPLICA": "Primary 와 그 Replica 동시 종료(slot 공백)",
}

runs = []
for f in glob.glob("results/*/runs.jsonl"):
    for line in open(f):
        line = line.strip()
        if line:
            r = json.loads(line)
            # smoke 와 설정 원복용(reset-*) 실행은 분석에서 제외
            if r["set"] != "smoke" and not (r.get("name") or "").split("/")[1:2] in (["reset-aof"], ["reset-minrep"], ["reset-all"]): runs.append(r)

def variant(r):
    parts = (r.get("name") or "").split("/")
    return parts[1] if r["set"] in ("options", "settings", "timing", "coverage") and len(parts) > 2 else ""

def g(r, *path):
    v = r
    for k in path:
        v = v.get(k) if isinstance(v, dict) else None
        if v is None: return None
    return v

def stats(vals):
    vals = [v for v in vals if v is not None]
    if not vals: return None
    return {"n": len(vals), "median": statistics.median(vals), "min": min(vals), "max": max(vals), "mean": statistics.fmean(vals),
            "stdev": statistics.stdev(vals) if len(vals) > 1 else 0.0, "values": vals}

def fmt(v, d=0, unit=""):
    if v is None: return "-"
    return (f"{v:,.{d}f}" if isinstance(v, float) or d else f"{v:,}") + unit

def rng(rs, *path, d=0, unit="", scale=1.0):
    st = stats([None if g(r, *path) is None else g(r, *path) * scale for r in rs])
    if not st: return "-"
    if st["n"] == 1 or st["min"] == st["max"]: return fmt(st["median"], d, unit)
    return f"{fmt(st['median'], d, unit)} [{fmt(st['min'], d)}–{fmt(st['max'], d)}]"

def group(pred):
    out = {}
    for r in runs:
        if pred(r): out.setdefault((variant(r), r["topology"], r["scenario"], r["rps"]), []).append(r)
    return out

out, summary = [], {"generated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), "runs": len(runs)}
def H(s): out.append("\n" + s + "\n")
def T(header, rows):
    out.append("| " + " | ".join(header) + " |")
    out.append("|" + "|".join(["---:" if i else "---" for i in range(len(header))]) + "|")
    for r in rows: out.append("| " + " | ".join(str(x) for x in r) + " |")
    out.append("")

out.append(f"# 측정 결과 (자동 생성: {summary['generated']}, 실행 {len(runs)}건)\n\n모든 수치는 실측값이다. 반복 측정은 `중앙값 [최소–최대]` 로 적고 반복별 값·표준편차는 부록에 있다. "
           "시각 정의: T0 장애 주입, T1 감지(Sentinel +odown / Cluster FAIL), T2 승격 시작(+try-failover / epoch 증가), T3 승격 완료(role:master), "
           "T4 앱이 새 Primary 에 연결, T5 첫 쓰기 성공, T6 토폴로지 안정. 생성: `bench/report.py`.\n")

# ---------- 1. 장애 시나리오 매트릭스 ----------
faults = group(lambda r: r["set"] == "faults")
if faults:
    H("## 1. 장애 시나리오별 Failover 시각 (Sentinel vs Cluster, 5회 중앙값)")
    out.append("단위 ms. 서비스 중단 = T5−T0, 여기서 T5 는 영향받은 shard 를 향한 쓰기가 처음 실패한 뒤 첫 성공한 시각(request_log 기준). "
               "쓰기 공백 = 첫 실패 → 첫 성공. 값이 없으면 그 사건이 일어나지 않았다는 뜻이다(예: Failover 없음, 쓰기 실패 없음).\n")
    scenarios = []
    for (v, t, sc, rps) in faults:
        if sc not in scenarios: scenarios.append(sc)
    order = list(SC_LABEL.keys())
    scenarios.sort(key=lambda s: order.index(s) if s in order else 99)
    rows = []
    for sc in scenarios:
        for t in TOPOS:
            rs = faults.get(("", t, sc, 300))
            if not rs: continue
            rows.append([SC_LABEL.get(sc, sc), LABEL[t], len(rs), rng(rs, "timings", "detect_ms"), rng(rs, "timings", "promote_ms"), rng(rs, "timings", "client_aware_ms"),
                         rng(rs, "timings", "first_fail_ms"), rng(rs, "timings", "outage_ms"), rng(rs, "timings", "write_gap_ms"), rng(rs, "timings", "stabilize_ms"), rng(rs, "timings", "rejoin_ms")])
            summary.setdefault("faults", {}).setdefault(sc, {})[t] = {k: stats([g(r, "timings", k) for r in rs]) for k in ["detect_ms", "promote_ms", "client_aware_ms", "first_fail_ms", "outage_ms", "write_gap_ms", "stabilize_ms", "rejoin_ms"]}
    T(["시나리오", "구성", "n", "감지 T1−T0", "승격 T3−T2", "클라이언트 인식 T4−T3", "첫 쓰기 실패 −T0", "서비스 중단 T5−T0", "쓰기 공백(첫 실패→첫 성공)", "안정화 T6−T0", "재합류(복구→안정)"], rows)

    H("### 1.1 장애 중 오류와 정합성 (장애 구간 40 s, 300 RPS, 명령 비율 GET50/SET20/INCR10/SEND15/MGET5)")
    rows = []
    for sc in scenarios:
        for t in TOPOS:
            rs = faults.get(("", t, sc, 300))
            if not rs: continue
            errs = {}
            for r in rs:
                for k, v in (g(r, "phases", "DURING", "errors") or {}).items(): errs[k] = errs.get(k, 0) + v
            top = ", ".join(f"{k} {v // len(rs):,}" for k, v in sorted(errs.items(), key=lambda kv: -kv[1])[:3])
            rows.append([SC_LABEL.get(sc, sc), LABEL[t], rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"), rng(rs, "phases", "DURING", "zero_success_seconds"),
                         rng(rs, "phases", "DURING", "p95_ms_median", d=2), top, rng(rs, "consistency", "lost_acked_writes"), rng(rs, "consistency", "acked_write_loss_pct", d=2, unit="%"),
                         rng(rs, "consistency", "duplicate_incr"), rng(rs, "consistency", "wrong_value_keys"), rng(rs, "phases", "AFTER", "error_rate_pct", d=2, unit="%")])
            summary["faults"][sc][t].update({"error_rate_during": stats([g(r, "phases", "DURING", "error_rate_pct") for r in rs]), "lost": stats([g(r, "consistency", "lost_acked_writes") for r in rs]),
                                             "dup_incr": stats([g(r, "consistency", "duplicate_incr") for r in rs]), "zero_success_seconds": stats([g(r, "phases", "DURING", "zero_success_seconds") for r in rs])})
    T(["시나리오", "구성", "장애 중 오류율", "성공 0건 초(s)", "장애 중 p95(ms, 초별 중앙값)", "주요 오류(회당 평균)", "승인 쓰기 유실", "유실률", "INCR 중복", "잘못된 값 키", "복구 후 오류율"], rows)

    # ---------- 2. 비교 결과표 (KILL_PRIMARY) ----------
    ks, kc = faults.get(("", "sentinel", "KILL_PRIMARY", 300)), faults.get(("", "cluster", "KILL_PRIMARY", 300))
    if ks and kc:
        H("## 2. 비교 결과표 — Primary 강제 종료(SIGKILL), 300 RPS, 5회 중앙값")
        def both(*path, d=0, unit=""):
            return rng(ks, *path, d=d, unit=unit), rng(kc, *path, d=d, unit=unit)
        rows = [["정상 상태 TPS(앱 워크로드 300 RPS 고정)", *both("phases", "BEFORE", "tps", d=1)], ["정상 상태 p95(ms)", *both("phases", "BEFORE", "p95_ms_median", d=2)],
                ["장애 감지 시간(ms)", *both("timings", "detect_ms")], ["Replica 승격 시간(ms)", *both("timings", "promote_ms")], ["클라이언트 인식(ms)", *both("timings", "client_aware_ms")],
                ["서비스 중단 시간(ms)", *both("timings", "outage_ms")], ["장애 중 오류율(40 s 창)", *both("phases", "DURING", "error_rate_pct", d=2, unit="%")],
                ["성공 0건인 초(s)", *both("phases", "DURING", "zero_success_seconds")], ["승인 쓰기 유실 수", *both("consistency", "lost_acked_writes")],
                ["중복 반영 수(INCR)", *both("consistency", "duplicate_incr")], ["토폴로지 안정화(ms)", *both("timings", "stabilize_ms")], ["옛 Primary 재합류(ms)", *both("timings", "rejoin_ms")],
                ["정상화 후 TPS", *both("phases", "AFTER", "tps", d=1)], ["정상화 후 오류율", *both("phases", "AFTER", "error_rate_pct", d=2, unit="%")]]
        T(["지표", "Sentinel", "Cluster"], rows)

# ---------- 3. 데이터 안전성 설정 ----------
settings = group(lambda r: r["set"] == "settings")
if settings:
    H("## 3. 데이터 안전성 설정 비교 — 복제 지연 500 ms 상태에서 Primary 강제 종료")
    out.append("설정만 바꾸고 같은 장애를 반복했다. 승인 = 앱이 성공 응답을 받은 쓰기(WAIT 사용 시 WAIT 가 채워진 쓰기). 쓰기 p95 는 장애 전(BEFORE) 구간 초별 p95 의 중앙값.\n")
    rows = []
    for (v, t, sc, rps), rs in sorted(settings.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        errs = {}
        for r in rs:
            for k, n in (g(r, "phases", "BEFORE", "errors") or {}).items(): errs[k] = errs.get(k, 0) + n
        derrs = {}
        for r in rs:
            for k, n in (g(r, "phases", "DURING", "errors") or {}).items(): derrs[k] = derrs.get(k, 0) + n
        rows.append([LABEL.get(t, t), v, SC_LABEL.get(sc, sc), len(rs), rng(rs, "consistency", "lost_acked_writes"), rng(rs, "consistency", "acked_write_loss_pct", d=2, unit="%"), rng(rs, "consistency", "duplicate_incr"),
                     rng(rs, "phases", "BEFORE", "p95_ms_median", d=2), rng(rs, "phases", "BEFORE", "error_rate_pct", d=2, unit="%"), ", ".join(f"{k} {n // len(rs)}" for k, n in sorted(derrs.items(), key=lambda kv: -kv[1])[:3]) or "-",
                     rng(rs, "timings", "outage_ms"), rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"), rng(rs, "consistency", "acked_writes_before_fault")])
        summary.setdefault("settings", {}).setdefault(t, {})[v] = {"lost": stats([g(r, "consistency", "lost_acked_writes") for r in rs]), "p95": stats([g(r, "phases", "BEFORE", "p95_ms_median") for r in rs]),
                                                                    "loss_pct": stats([g(r, "consistency", "acked_write_loss_pct") for r in rs])}
    T(["구성", "설정", "시나리오", "n", "승인 쓰기 유실", "유실률", "INCR 중복", "정상 시 p95(ms)", "정상 시 오류율", "장애 중 주요 오류(회당)", "서비스 중단(ms)", "장애 중 오류율", "장애 전 승인 쓰기"], rows)

# ---------- 4. Lettuce 옵션 ----------
options = group(lambda r: r["set"] == "options")
if options:
    H("## 4. Lettuce 옵션 비교 — Primary 강제 종료, 300 RPS")
    rows = []
    for (v, t, sc, rps), rs in sorted(options.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        errs = {}
        for r in rs:
            for k, n in (g(r, "phases", "DURING", "errors") or {}).items(): errs[k] = errs.get(k, 0) + n
        rows.append([LABEL.get(t, t), v, SC_LABEL.get(sc, sc), len(rs), rng(rs, "timings", "outage_ms"), rng(rs, "timings", "client_aware_ms"), rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"),
                     rng(rs, "phases", "DURING", "zero_success_seconds"), ", ".join(f"{k} {n // len(rs)}" for k, n in sorted(errs.items(), key=lambda kv: -kv[1])[:3]) or "-",
                     rng(rs, "consistency", "duplicate_incr"), rng(rs, "consistency", "lost_acked_writes"), rng(rs, "phases", "AFTER", "error_rate_pct", d=2, unit="%")])
        summary.setdefault("options", {}).setdefault(t, {})[v] = {"outage": stats([g(r, "timings", "outage_ms") for r in rs]), "error_rate_during": stats([g(r, "phases", "DURING", "error_rate_pct") for r in rs]),
                                                                   "dup_incr": stats([g(r, "consistency", "duplicate_incr") for r in rs]), "zero_success_seconds": stats([g(r, "phases", "DURING", "zero_success_seconds") for r in rs])}
    T(["구성", "옵션", "시나리오", "n", "서비스 중단(ms)", "클라이언트 인식(ms)", "장애 중 오류율", "성공 0건 초", "주요 오류", "INCR 중복", "승인 쓰기 유실", "복구 후 오류율"], rows)

# ---------- 5. 감지 시간 설정 민감도 ----------
timing = group(lambda r: r["set"] == "timing")
if timing:
    H("## 5. 감지 시간 설정 민감도 — down-after-milliseconds / cluster-node-timeout")
    rows = []
    for (v, t, sc, rps), rs in sorted(timing.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        rows.append([LABEL.get(t, t), v, SC_LABEL.get(sc, sc), len(rs), rng(rs, "timings", "detect_ms"), rng(rs, "timings", "outage_ms"), rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"), rng(rs, "phases", "BEFORE", "error_rate_pct", d=2, unit="%"), rng(rs, "consistency", "lost_acked_writes"), rng(rs, "consistency", "duplicate_incr"), rng(rs, "timings", "rejoin_ms")])
        summary.setdefault("timing", {}).setdefault(t, {})[v] = {"detect": stats([g(r, "timings", "detect_ms") for r in rs]), "outage": stats([g(r, "timings", "outage_ms") for r in rs])}
    T(["구성", "설정", "시나리오", "n", "감지(ms)", "서비스 중단(ms)", "장애 중 오류율", "정상 시 오류율(오탐)", "승인 쓰기 유실", "INCR 중복", "재합류(ms)"], rows)

coverage = group(lambda r: r["set"] == "coverage")
if coverage:
    H("## 6. cluster-require-full-coverage — Primary 와 Replica 동시 종료(slot 공백)")
    rows = []
    for (v, t, sc, rps), rs in sorted(coverage.items()):
        errs = {}
        for r in rs:
            for k, n in (g(r, "phases", "DURING", "errors") or {}).items(): errs[k] = errs.get(k, 0) + n
        rows.append([v, len(rs), rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"), rng(rs, "phases", "DURING", "zero_success_seconds"), ", ".join(f"{k} {n // len(rs)}" for k, n in errs.items()), rng(rs, "timings", "rejoin_ms")])
        summary.setdefault("coverage", {})[v] = {"error_rate_during": stats([g(r, "phases", "DURING", "error_rate_pct") for r in rs])}
    T(["설정", "n", "장애 중 오류율", "성공 0건 초", "오류 종류", "복구 후 안정화(ms)"], rows)

# ---------- 7. k6 성능 ----------
perf = group(lambda r: r["set"] == "perf" and r.get("driver") == "k6")
if perf:
    H("## 7. 동일 부하 성능 비교 (k6, HTTP, 워밍업 1분 → 정상 2분 → Primary 강제 종료 2분 → 복구 2분, 5회 중앙값)")
    out.append("k6 `constant-arrival-rate` 로 요청률을 고정했다. 오류율 = 1 − OK 응답/요청. 정상·장애·복구 구간은 k6 시나리오 태그로 분리했다.\n")
    for ph, title in [("before", "정상 구간"), ("during", "장애 구간(Primary 종료 후 2분)"), ("after", "복구 구간")]:
        rows = []
        for rps in sorted({k[3] for k in perf}):
            for t in TOPOS:
                rs = perf.get(("", t, "KILL_PRIMARY", rps))
                if not rs: continue
                rows.append([f"{rps:,}", LABEL[t], len(rs), rng(rs, "k6", ph, "rps", d=1), rng(rs, "k6", ph, "avg_ms", d=2), rng(rs, "k6", ph, "p50_ms", d=2), rng(rs, "k6", ph, "p95_ms", d=2),
                             rng(rs, "k6", ph, "p99_ms", d=2), rng(rs, "k6", ph, "max_ms", d=1), rng(rs, "k6", ph, "error_rate_pct", d=2, unit="%")])
                summary.setdefault("perf", {}).setdefault(str(rps), {}).setdefault(t, {})[ph] = {k: stats([g(r, "k6", ph, k) for r in rs]) for k in ["rps", "p95_ms", "p99_ms", "error_rate_pct"]}
        H(f"### 7.{['before', 'during', 'after'].index(ph) + 1} {title}")
        T(["목표 RPS", "구성", "n", "실제 RPS", "평균(ms)", "p50", "p95", "p99", "최대", "오류율"], rows)
    def rngf(rs, fn, d=0, unit=""):
        vals = []
        for r in rs:
            try: vals.append(fn(r))
            except Exception: vals.append(None)
        st = stats(vals)
        if not st: return "-"
        if st["n"] == 1 or st["min"] == st["max"]: return fmt(st["median"], d, unit)
        return f"{fmt(st['median'], d, unit)} [{fmt(st['min'], d)}–{fmt(st['max'], d)}]"
    def redis_cpu_max(r):
        dk = g(r, "resources", "docker") or {}
        vals = [v.get("cpu_max_pct") for k, v in dk.items() if k != "lab-app" and v.get("cpu_max_pct") is not None]
        return max(vals) if vals else None
    rows = []
    for rps in sorted({k[3] for k in perf}):
        for t in TOPOS:
            rs = perf.get(("", t, "KILL_PRIMARY", rps))
            if not rs: continue
            rows.append([f"{rps:,}", LABEL[t], rng(rs, "timings", "detect_ms"), rng(rs, "timings", "outage_ms"), rng(rs, "consistency", "lost_acked_writes"), rng(rs, "consistency", "duplicate_incr"), rng(rs, "timings", "rejoin_ms"),
                         rng(rs, "resources", "docker", "lab-app", "cpu_avg_pct", d=1), rng(rs, "resources", "docker", "lab-app", "cpu_max_pct", d=1), rng(rs, "resources", "docker", "lab-app", "mem_max_mb", d=0), rngf(rs, redis_cpu_max, d=1)])
            summary["perf"][str(rps)][t]["resources"] = {"app_cpu_avg": stats([g(r, "resources", "docker", "lab-app", "cpu_avg_pct") for r in rs]), "app_cpu_max": stats([g(r, "resources", "docker", "lab-app", "cpu_max_pct") for r in rs]),
                                                          "app_mem_max_mb": stats([g(r, "resources", "docker", "lab-app", "mem_max_mb") for r in rs]), "redis_cpu_max": stats([redis_cpu_max(r) for r in rs])}
    H("### 7.4 부하 단계별 Failover 시각·정합성·자원 (docker stats 5 s 표본, 실험 전체 구간)")
    T(["목표 RPS", "구성", "감지(ms)", "서비스 중단(ms)", "승인 쓰기 유실", "INCR 중복", "재합류(ms)", "앱 CPU 평균(%)", "앱 CPU 최대(%)", "앱 메모리 최대(MB)", "Redis 노드 CPU 최대(%)"], rows)

# ---------- 8·9. 기록 방식, 앱↔Sentinel 경로(Toxiproxy) ----------
misc = [r for r in runs if r["set"] == "misc"]
TOXI_LABEL = {"TOXIC_APP_SENTINEL_TIMEOUT": "앱↔Sentinel 응답 없음(timeout toxic)", "TOXIC_APP_SENTINEL_LATENCY": "앱↔Sentinel 지연 300 ms(latency toxic)"}
if misc:
    def mvariant(r):
        parts = (r.get("name") or "").split("/")
        return r.get("variant") or (parts[1] if len(parts) > 2 else "")
    rec, toxi = {}, {}
    for r in misc:
        v = mvariant(r)
        (rec if v.startswith("recording-") else toxi).setdefault(v, []).append(r)
    if rec:
        H("## 8. 실험 기록 방식이 측정에 미치는 영향 — Sentinel, 1,000 RPS(앱 내부 워크로드), 장애 없음, 60 s")
        out.append("요청 경로에서 기록을 분리한 세 방식(ADR-005)을 같은 부하로 비교했다. p95/p99 는 초별 값의 중앙값, 앱 CPU 는 Prometheus `process_cpu_usage` 60 s 평균(코어 비율; 1.0 = 코어 1개).\n")
        rows = []
        for v, rs in sorted(rec.items(), key=lambda kv: ["recording-MEMORY", "recording-BATCH", "recording-SAMPLED"].index(kv[0]) if kv[0] in ("recording-MEMORY", "recording-BATCH", "recording-SAMPLED") else 9):
            rows.append([v[len("recording-"):], len(rs), rng(rs, "phases", "BEFORE", "tps", d=1), rng(rs, "phases", "BEFORE", "p95_ms_median", d=3), rng(rs, "phases", "BEFORE", "p99_ms_median", d=3), rng(rs, "phases", "BEFORE", "p95_ms_max", d=2),
                         rng(rs, "phases", "BEFORE", "error_rate_pct", d=2, unit="%"), rng(rs, "app_cpu", d=3)])
            summary.setdefault("misc", {}).setdefault("recording", {})[v] = {"p95": stats([g(r, "phases", "BEFORE", "p95_ms_median") for r in rs]), "p99": stats([g(r, "phases", "BEFORE", "p99_ms_median") for r in rs]), "app_cpu": stats([r.get("app_cpu") for r in rs])}
        T(["기록 방식", "n", "TPS", "p95(ms)", "p99(ms)", "p95 최대(ms)", "오류율", "앱 CPU(코어 비율)"], rows)
    if toxi:
        H("## 9. 앱↔Sentinel 경로 장애 (Toxiproxy) — Sentinel, 300 RPS, 장애 40 s")
        out.append("앱이 Sentinel 에 닿지 못하거나 늦게 닿을 때 이미 연결된 Primary 로의 명령이 계속되는지를 본다. Toxiproxy 는 주소가 정적인 이 경로에만 쓴다(ADR-003).\n")
        rows = []
        for v, rs in sorted(toxi.items()):
            errs = {}
            for r in rs:
                for k, n in (g(r, "phases", "DURING", "errors") or {}).items(): errs[k] = errs.get(k, 0) + n
            rows.append([TOXI_LABEL.get(v, v), len(rs), rng(rs, "phases", "BEFORE", "p95_ms_median", d=2), rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"), rng(rs, "phases", "DURING", "p95_ms_median", d=2),
                         ", ".join(f"{k} {n // len(rs)}" for k, n in sorted(errs.items(), key=lambda kv: -kv[1])[:3]) or "-", rng(rs, "timings", "outage_ms"), rng(rs, "consistency", "lost_acked_writes"), rng(rs, "phases", "AFTER", "error_rate_pct", d=2, unit="%")])
            summary.setdefault("misc", {}).setdefault("toxi", {})[v] = {"error_rate_during": stats([g(r, "phases", "DURING", "error_rate_pct") for r in rs]), "outage": stats([g(r, "timings", "outage_ms") for r in rs])}
        T(["시나리오", "n", "정상 시 p95(ms)", "장애 중 오류율", "장애 중 p95(ms)", "주요 오류(회당)", "서비스 중단(ms)", "승인 쓰기 유실", "복구 후 오류율"], rows)

# ---------- 부록 D: 이상치와 원인 ----------
def outlier_causes(r):
    causes = []
    ev = [e[2] for e in r.get("events", [])]
    if any(t == "+tilt" for t in ev): causes.append("Sentinel TILT 모드(시계 점프 감지 → 30 s 판단 보류; 호스트 절전으로 VM 시계가 뛴 것으로 추정)")
    if (g(r, "phases", "BEFORE", "error_rate_pct") or 0) > 1: causes.append("기준 구간 오염(장애 전 오류)")
    dk = (g(r, "resources", "docker") or {}).get("lab-app", {})
    if dk.get("cpu_max_pct", 0) > 180: causes.append(f"앱 CPU 최대 {dk['cpu_max_pct']}%")
    return causes
H("## 부록 D. 이상치 (서비스 중단이 같은 그룹 중앙값의 2배 초과 또는 미달 절반) 와 원인")
rows = []
for (v, t, sc, rps), rs in sorted(faults.items(), key=lambda kv: (kv[0][2], kv[0][1])):
    st = stats([g(r, "timings", "outage_ms") for r in rs])
    if not st or st["n"] < 3: continue
    for r in rs:
        o = g(r, "timings", "outage_ms")
        if o is None or st["median"] == 0: continue
        if o > 2 * st["median"] or o < st["median"] / 2:
            rows.append([SC_LABEL.get(sc, sc), LABEL[t], r["id"], f"{o:,}", f"{st['median']:,.0f}", "; ".join(outlier_causes(r)) or "원인 미확인(로그 참조)"])
T(["시나리오", "구성", "실행", "서비스 중단(ms)", "그룹 중앙값", "확인된 원인"], rows or [["-", "-", "-", "-", "-", "이상치 없음"]])
out.append("TILT: Sentinel 은 타이머 루프 사이 간격이 음수이거나 2 초를 넘으면(시계 점프·프로세스 정지) 30 초 동안 장애 판단을 멈춘다. 이 장비는 유휴 1분 뒤 시스템 절전(pmset sleep 1)이 걸려 있어 Docker VM 이 통째로 멈췄다 깨어나면 세 Sentinel 이 동시에 TILT 에 들어간다(이후 배치는 caffeinate 로 절전을 막고 실행). 이 값은 중앙값 계산에는 포함하되 최소·최대 범위에서 그 원인을 밝힌다.\n")

# ---------- 부록 C: 설정 영속화 전 Sentinel 결과(함정 증거) ----------
imm = group(lambda r: r["set"] == "faults-immutable")
if imm:
    H("## 부록 C. 설정 영속화 전(읽기 전용 redis.conf + 명령줄 --replicaof) Sentinel 1차 측정 — ADR-006 의 증거")
    out.append("재시작한 노드가 부트스트랩 역할로 되돌아가는 구성에서 잰 값이라 본문 표와 직접 비교하지 않는다. 오염된 9회(기준 구간 READONLY)는 제외했다.\n")
    rows = []
    for (v, t, sc, rps), rs in sorted(imm.items(), key=lambda kv: list(SC_LABEL).index(kv[0][2]) if kv[0][2] in SC_LABEL else 99):
        rows.append([SC_LABEL.get(sc, sc), len(rs), rng(rs, "timings", "detect_ms"), rng(rs, "timings", "outage_ms"), rng(rs, "timings", "rejoin_ms"), rng(rs, "phases", "DURING", "error_rate_pct", d=2, unit="%"),
                     rng(rs, "phases", "AFTER", "error_rate_pct", d=2, unit="%"), rng(rs, "consistency", "lost_acked_writes"), rng(rs, "consistency", "duplicate_incr")])
    T(["시나리오", "n", "감지(ms)", "서비스 중단(ms)", "재합류(ms)", "장애 중 오류율", "복구 후 오류율", "승인 쓰기 유실", "INCR 중복"], rows)

# ---------- 부록: 반복값 ----------
H("## 부록 A. 반복 측정값 (서비스 중단 ms: 5회 값 / 평균 / 표준편차)")
rows = []
for (v, t, sc, rps), rs in sorted(faults.items(), key=lambda kv: (kv[0][2], kv[0][1])):
    st = stats([g(r, "timings", "outage_ms") for r in rs])
    if not st: continue
    rows.append([SC_LABEL.get(sc, sc), LABEL[t], ", ".join(f"{int(x):,}" for x in st["values"]), fmt(st["mean"], 0), fmt(st["stdev"], 0), fmt(st["stdev"] / st["mean"] * 100 if st["mean"] else 0, 1, "%")])
T(["시나리오", "구성", "5회 값", "평균", "표준편차", "변동계수"], rows)
H("## 부록 B. 반복 측정값 (승인 쓰기 유실 건수)")
rows = []
for (v, t, sc, rps), rs in sorted(list(faults.items()) + list(settings.items()), key=lambda kv: (kv[0][2], kv[0][1], kv[0][0])):
    st = stats([g(r, "consistency", "lost_acked_writes") for r in rs])
    if not st: continue
    rows.append([SC_LABEL.get(sc, sc) + (f" / {v}" if v else ""), LABEL[t], ", ".join(f"{int(x):,}" for x in st["values"]), fmt(st["mean"], 1), fmt(st["max"])])
T(["시나리오", "구성", "값", "평균", "최대"], rows)

os.makedirs("docs/charts", exist_ok=True)
open("docs/results.md", "w").write("\n".join(out))

# ---------- charts ----------
def bars_by_scenario(metric_path, title, ylabel, fname, scale=1.0, scenarios=None):
    scs = scenarios or [sc for sc in SC_LABEL if any(("", t, sc, 300) in faults for t in TOPOS)]
    if not scs: return
    fig, ax = plt.subplots(figsize=(11, 4.6))
    w = 0.38
    for i, t in enumerate(TOPOS):
        xs, ys, lo, hi = [], [], [], []
        for j, sc in enumerate(scs):
            st = stats([None if g(r, *metric_path) is None else g(r, *metric_path) * scale for r in faults.get(("", t, sc, 300), [])])
            if not st: continue
            xs.append(j + (i - 0.5) * w); ys.append(st["median"]); lo.append(st["median"] - st["min"]); hi.append(st["max"] - st["median"])
        if xs:
            ax.bar(xs, ys, w, color=COLOR[t], label=LABEL[t], yerr=[lo, hi], capsize=3, error_kw={"lw": 1, "ecolor": "#555"})
            for x, y in zip(xs, ys): ax.text(x, y, f"{y:,.0f}", ha="center", va="bottom", fontsize=7)
    ax.set_xticks(range(len(scs))); ax.set_xticklabels([SC_LABEL[s].replace("(", "\n(") for s in scs], fontsize=7.5)
    ax.set_ylabel(ylabel); ax.set_title(title, fontsize=11); ax.legend(frameon=False); ax.grid(axis="y", color="#e5e7eb"); ax.set_axisbelow(True)
    for s in ["top", "right"]: ax.spines[s].set_visible(False)
    fig.tight_layout(); fig.savefig(f"docs/charts/{fname}"); plt.close(fig)

if faults:
    bars_by_scenario(("timings", "outage_ms"), "시나리오별 서비스 중단 시간 (T5−T0, 5회 중앙값, 오차선=최소·최대)", "ms", "outage_by_scenario.png")
    bars_by_scenario(("phases", "DURING", "error_rate_pct"), "시나리오별 장애 중 오류율 (40 s 창, 300 RPS)", "%", "error_rate_by_scenario.png")
    bars_by_scenario(("consistency", "lost_acked_writes"), "시나리오별 승인 쓰기 유실 건수", "건", "lost_writes_by_scenario.png")
    # Failover 단계 분해 (KILL)
    fig, ax = plt.subplots(figsize=(7.5, 3.4))
    phases = [("detect_ms", "감지 T1−T0", "#8a94a0"), ("promote_ms", "승격 T3−T2", "#eda100"), ("client_reconnect_ms", "재연결·첫 쓰기 T5−T3", "#d93a36")]
    for i, t in enumerate(TOPOS):
        rs = faults.get(("", t, "KILL_PRIMARY", 300), [])
        left = 0
        for k, lab, c in phases:
            st = stats([g(r, "timings", k) for r in rs])
            v = max(0, st["median"]) if st else 0
            ax.barh(i, v, left=left, color=c, label=lab if i == 0 else None, height=0.5)
            if v > 200: ax.text(left + v / 2, i, f"{v:,.0f}", ha="center", va="center", fontsize=8, color="white")
            left += v
        ax.text(left + 100, i, f"합계 {left:,.0f} ms", va="center", fontsize=8)
    ax.set_yticks(range(len(TOPOS))); ax.set_yticklabels([LABEL[t] for t in TOPOS]); ax.set_xlabel("ms"); ax.invert_yaxis()
    ax.set_title("Primary 강제 종료 시 Failover 단계별 시간 (5회 중앙값)", fontsize=10); ax.legend(frameon=False, fontsize=8, loc="lower right"); ax.grid(axis="x", color="#e5e7eb"); ax.set_axisbelow(True)
    for s in ["top", "right"]: ax.spines[s].set_visible(False)
    fig.tight_layout(); fig.savefig("docs/charts/failover_breakdown_kill.png"); plt.close(fig)

import re
def parse_time(v):
    """ISO(나노초 포함)·epoch ms·MySQL 문자열을 tz-aware datetime 으로."""
    if isinstance(v, (int, float)): return datetime.datetime.fromtimestamp(v / 1000, tz=datetime.timezone.utc)
    s = str(v).replace("Z", "+00:00")
    s = re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], s)
    if "T" not in s: s = s.replace(" ", "T")
    dt = datetime.datetime.fromisoformat(s)
    if dt.tzinfo is None: dt = dt.replace(tzinfo=datetime.timezone(datetime.timedelta(hours=9)))
    return dt

def timeline_chart(rs, fname, title):
    """중단 시간이 중앙값인 실행의 초별 성공/실패와 T 마커."""
    rs = [r for r in rs if g(r, "timings", "outage_ms") is not None]
    if not rs: return
    rs.sort(key=lambda r: r["timings"]["outage_ms"])
    r = rs[len(rs) // 2]
    f = f"results/{r['set']}/raw/{r['id']}.samples.json"
    if not os.path.exists(f): return
    samples = json.load(open(f))
    t0 = parse_time(r["t"]["t0_inject"])
    def sec(at): return (parse_time(at) - t0).total_seconds()
    xs = [sec(s["at"]) for s in samples]; ok = [s["ok"] for s in samples]; fail = [s["fail"] for s in samples]
    fig, ax = plt.subplots(figsize=(10, 3.4))
    ax.bar(xs, ok, 1.0, color="#1b8a5a", label="성공"); ax.bar(xs, fail, 1.0, bottom=ok, color="#d93a36", label="실패")
    for key, lab in [("t1_detected", "T1"), ("t3_promoted", "T3"), ("t5_first_write_ok", "T5"), ("recoveredAt", "복구")]:
        v = r["t"].get(key)
        if v: x = sec(v); ax.axvline(x, color="#111", lw=0.8, ls="--"); ax.text(x, max(ok + [1]) * 1.02, lab, fontsize=8, ha="center")
    ax.axvline(0, color="#111", lw=1.2); ax.text(0, max(ok + [1]) * 1.02, "T0", fontsize=8, ha="center")
    ax.set_xlim(-35, max(xs) + 1); ax.set_xlabel("장애 주입 후 초"); ax.set_ylabel("요청/초"); ax.set_title(title, fontsize=10); ax.legend(frameon=False, fontsize=8)
    for s in ["top", "right"]: ax.spines[s].set_visible(False)
    fig.tight_layout(); fig.savefig(f"docs/charts/{fname}"); plt.close(fig)

for t in TOPOS:
    timeline_chart(faults.get(("", t, "KILL_PRIMARY", 300), []), f"timeline_{t}_kill.png", f"{LABEL[t]}: Primary 강제 종료 전후 초당 성공·실패 (중단 시간이 중앙값인 실행)")

if settings:
    fig, axes = plt.subplots(1, 2, figsize=(11, 3.8))
    for ax, (key, lab) in zip(axes, [(("consistency", "lost_acked_writes"), "승인 쓰기 유실(건)"), (("phases", "BEFORE", "p95_ms_median"), "정상 시 p95(ms)")]):
        vs = sorted({k[0] for k in settings}, key=lambda s: ["baseline", "min-replicas", "wait-1", "aof-everysec", "aof-always"].index(s) if s in ["baseline", "min-replicas", "wait-1", "aof-everysec", "aof-always"] else 9)
        w = 0.38
        for i, t in enumerate(TOPOS):
            ys, xs = [], []
            for j, v in enumerate(vs):
                st = stats([g(r, *key) for r in settings.get((v, t, "NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY", 300), [])])
                if st: xs.append(j + (i - 0.5) * w); ys.append(st["median"])
            if xs:
                ax.bar(xs, ys, w, color=COLOR[t], label=LABEL[t])
                for x, y in zip(xs, ys): ax.text(x, y, f"{y:,.1f}" if y < 10 else f"{y:,.0f}", ha="center", va="bottom", fontsize=7)
        ax.set_xticks(range(len(vs))); ax.set_xticklabels(vs, fontsize=8); ax.set_title(lab, fontsize=10); ax.legend(frameon=False, fontsize=8); ax.grid(axis="y", color="#e5e7eb"); ax.set_axisbelow(True)
        for s in ["top", "right"]: ax.spines[s].set_visible(False)
    fig.suptitle("데이터 안전성 설정 비교 (복제 지연 500 ms + Primary 강제 종료, 5회 중앙값)", fontsize=10); fig.tight_layout(); fig.savefig("docs/charts/settings_loss_vs_latency.png"); plt.close(fig)

if options:
    fig, axes = plt.subplots(1, 2, figsize=(12, 3.8))
    for ax, (key, lab) in zip(axes, [(("timings", "outage_ms"), "서비스 중단(ms)"), (("phases", "DURING", "error_rate_pct"), "장애 중 오류율(%)")]):
        items = sorted(options.items(), key=lambda kv: (kv[0][1], kv[0][0]))
        labels = [f"{LABEL[k[1]]}\n{k[0]}" for k, _ in items]
        ys = [stats([g(r, *key) for r in rs]) for _, rs in items]
        ax.bar(range(len(items)), [y["median"] if y else 0 for y in ys], color=[COLOR[k[1]] for k, _ in items])
        for i, y in enumerate(ys):
            if y: ax.text(i, y["median"], f"{y['median']:,.0f}" if y["median"] > 20 else f"{y['median']:,.2f}", ha="center", va="bottom", fontsize=7)
        ax.set_xticks(range(len(items))); ax.set_xticklabels(labels, fontsize=7); ax.set_title(lab, fontsize=10); ax.grid(axis="y", color="#e5e7eb"); ax.set_axisbelow(True)
        for s in ["top", "right"]: ax.spines[s].set_visible(False)
    fig.suptitle("Lettuce 옵션별 Failover 영향 (Primary 강제 종료, 5회 중앙값)", fontsize=10); fig.tight_layout(); fig.savefig("docs/charts/options_outage_error.png"); plt.close(fig)

if perf:
    rpss = sorted({k[3] for k in perf})
    fig, axes = plt.subplots(1, 4, figsize=(15, 3.6))
    for ax, (ph, key, lab) in zip(axes, [("before", "p95_ms", "정상 구간 p95(ms)"), ("during", "error_rate_pct", "장애 구간 오류율(%)"), ("during", "p99_ms", "장애 구간 p99(ms)"), ("app_cpu", None, "앱 CPU 평균(%)")]):
        for t in TOPOS:
            if ph == "app_cpu": ys = [stats([g(r, "resources", "docker", "lab-app", "cpu_avg_pct") for r in perf.get(("", t, "KILL_PRIMARY", rps), [])]) for rps in rpss]
            else: ys = [stats([g(r, "k6", ph, key) for r in perf.get(("", t, "KILL_PRIMARY", rps), [])]) for rps in rpss]
            ax.plot(rpss, [y["median"] if y else None for y in ys], marker="o", color=COLOR[t], label=LABEL[t], lw=2)
            ax.fill_between(rpss, [y["min"] if y else 0 for y in ys], [y["max"] if y else 0 for y in ys], color=COLOR[t], alpha=0.12)
        ax.set_xscale("log"); ax.set_xticks(rpss); ax.set_xticklabels([f"{r:,}" for r in rpss]); ax.set_xlabel("목표 RPS"); ax.set_title(lab, fontsize=10); ax.grid(color="#e5e7eb"); ax.legend(frameon=False, fontsize=8)
        for s in ["top", "right"]: ax.spines[s].set_visible(False)
    fig.suptitle("k6 부하 단계별 성능 (5회 중앙값, 음영=최소·최대)", fontsize=10); fig.tight_layout(); fig.savefig("docs/charts/perf_by_rps.png"); plt.close(fig)

json.dump(summary, open("results/summary.json", "w"), ensure_ascii=False, indent=1, default=str)
print(f"wrote docs/results.md, charts, summary.json (runs={len(runs)})")

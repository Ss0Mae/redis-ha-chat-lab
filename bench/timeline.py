"""실험 1건 → docs/timelines/<id>.md (스펙 17절 양식). 사용: python3 bench/timeline.py <set> <experimentId> [가설] [결론]"""
import json, sys, os, re, datetime
set_, eid = sys.argv[1], sys.argv[2]
hyp = sys.argv[3] if len(sys.argv) > 3 else ""
concl = sys.argv[4] if len(sys.argv) > 4 else ""
d = json.load(open(f"results/{set_}/raw/{eid}.experiment.json"))
s = d.get("summary", {}); t = s.get("timings", {}); c = s.get("consistency", {}); ph = s.get("phases", {}); lt = s.get("log_timings", {})
def kst(v):
    if not v: return "-"
    v = re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000")[:3], str(v).replace("Z", "+00:00"))
    return datetime.datetime.fromisoformat(v).astimezone(datetime.timezone(datetime.timedelta(hours=9))).strftime("%H:%M:%S.%f")[:-3]
def ms(k): return "-" if t.get(k) is None else f"{t[k]:,} ms"
fault = d.get("fault") or s.get("fault") or {}
wl = d.get("workload") or {}
rows = [
 ("실험 이름", d.get("name")), ("검증할 가설", hyp or d.get("hypothesis") or "-"),
 ("Redis 구성", {"sentinel": "Primary 1 + Replica 2 + Sentinel 3 (quorum 2, down-after 5 s)", "cluster": "Primary 3 + Replica 3, 16,384 slot, node-timeout 5 s"}.get(d.get("topology"), d.get("topology"))),
 ("클라이언트 설정", "Spring Boot 3.5 / Lettuce 6.6, command timeout 1 s, autoReconnect, " + ("adaptive+periodic(5 s) refresh" if d.get("topology") == "cluster" else "Sentinel pubsub 구독") + f", Redis 설정 {d.get('redisSettings') or s.get('redisSettings') or {}}"),
 ("부하 조건", f"{wl.get('rps')} RPS, GET/SET/INCR/SEND/MGET = {wl.get('mix', {})}, 사용자 {wl.get('users')}, 방 {wl.get('rooms')}" if wl else "k6 HTTP"),
 ("장애 대상", s.get("failedNode") or d.get("failedNode")), ("장애 방식", f"{fault.get('scenario')} (target={fault.get('target')}, delayMs={fault.get('delayMs')}, lossPct={fault.get('lossPct')})"),
 ("T0 장애 주입", kst(t.get("t0_inject"))), ("T1 장애 감지", kst(t.get("t1_detected")) + (f" (로그 {kst(lt['t1_log_fail_quorum']['at'])})" if lt.get("t1_log_fail_quorum") else "")),
 ("T2 승격 시작", kst(t.get("t2_promotion_start")) + (f" (로그 {kst(lt['t2_log_election_delayed']['at'])})" if lt.get("t2_log_election_delayed") else "")),
 ("T3 승격 완료", kst(t.get("t3_promoted")) + (f" (로그 {kst(lt['t3_log_election_won']['at'])})" if lt.get("t3_log_election_won") else "") + (f", +switch-master {kst(t.get('t3b_switch_master'))}" if t.get("t3b_switch_master") else "")),
 ("T4 클라이언트 인식", kst(t.get("t4_client_aware"))), ("T5 첫 쓰기 성공", kst(t.get("t5_first_write_ok"))), ("T6 토폴로지 안정화", kst(t.get("t6_stable"))),
 ("서비스 중단 시간", ms("outage_ms") + f" (감지 {ms('detect_ms')}, 승격 {ms('promote_ms')}, 재연결 {ms('client_reconnect_ms')})"),
 ("실패 요청 수", f"{ph.get('DURING', {}).get('fail', '-')} / {ph.get('DURING', {}).get('ok', 0) + ph.get('DURING', {}).get('fail', 0)} (장애 구간 오류율 {ph.get('DURING', {}).get('error_rate_pct', '-')}%)"),
 ("승인 쓰기 유실 수", f"{c.get('lost_acked_writes', '-')} / 장애 전 승인 {c.get('acked_writes_before_fault', '-')} (유실률 {c.get('acked_write_loss_pct', '-')}%, 메시지 {c.get('acked_messages_lost', '-')}, INCR {c.get('lost_incr', '-')}, 잘못된 값 {c.get('wrong_value_keys', '-')})"),
 ("중복 처리 수", f"INCR 중복 {c.get('duplicate_incr', '-')}, 스트림 중복 {c.get('duplicate_stream_entries', '-')}"),
 ("원인 분석", "(결과 해석은 docs/experiments 참고)"), ("복구 과정", f"복구 시작 {kst(t.get('recoveredAt'))} → 재합류 안정 {kst(t.get('recoveredStableAt'))} ({ms('rejoin_ms')}); 재동기화: " + ("full" if lt.get("rejoin_log_full_resync") or lt.get("rejoin_log_partial_rejected") else "partial" if lt.get("rejoin_log_partial_resync") else "-")),
 ("결론", concl or "-"), ("현재 구조의 한계", "폴링 200 ms 해상도(로그로 보정), 한 장비의 VM 을 앱·Redis·부하가 공유"),
]
os.makedirs("docs/timelines", exist_ok=True)
body = "```text\n" + "\n".join(f"{k}: {v}" for k, v in rows) + "\n```\n\n주요 이벤트:\n\n| 시각(KST) | 출처 | 이벤트 | 내용 |\n|---|---|---|---|\n"
for e in d.get("events", []):
    if e["source"] == "lettuce" and e["type"] not in ("ConnectedEvent", "ClusterTopologyChangedEvent"): continue
    body += f"| {kst(e['at'])} | {e['source']} | {e['type']} | {str(e['detail'])[:100].replace('|', '/')} |\n"
open(f"docs/timelines/{eid}.md", "w").write(f"# 타임라인 {eid}\n\n" + body)
print(f"docs/timelines/{eid}.md")

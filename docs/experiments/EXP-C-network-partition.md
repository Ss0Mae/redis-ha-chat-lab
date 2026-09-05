# EXP-C. 네트워크 단절·지연·손실 — 프로세스 종료와 무엇이 다른가

```text
실험 이름: faults/PARTITION_PRIMARY_FROM_SENTINELS, PARTITION_PRIMARY_FROM_REPLICAS, PARTITION_PRIMARY_FROM_APP (Sentinel),
          PARTITION_PRIMARY_FROM_PEERS, PARTITION_PRIMARY_FROM_APP (Cluster), NETEM_GLOBAL_DELAY 200 ms, NETEM_LOSS 5 % (둘 다) — 각 5회
검증하려는 가설: 파티션은 "누가 보느냐" 에 따라 결과가 갈린다. 감시자만 못 보면 Failover 가 일어나되 앱은 옛 Primary 에 남아 split-brain 이 생기고,
                앱만 못 보면 Failover 가 없어 앱 혼자 멈춘다. 복제만 끊기면 아무 일도 없다. 지연·손실은 감지 시간을 바꾸지 않는다.
변경한 항목: Primary 노드의 네트워크 네임스페이스를 공유하는 사이드카에서 iptables 로 상대(Sentinel 3대 / Replica / 앱 / 다른 Cluster 노드)의 IP 만 양방향 DROP.
            지연·손실은 모든 Redis 노드 송신에 tc netem(delay 200 ms / loss 5 %). 복구는 규칙 삭제.
통제한 조건: EXP-A/B 와 같음 (300 RPS, 2 분, 5회, Redis·Lettuce 설정 동일).
서버 및 DB 사양: EXP-A/B 와 같음.
테스트 데이터·동시 사용자·전체 요청 수·테스트 지속시간: EXP-A/B 와 같음.
```

## 측정 결과 (5회 중앙값 [최소–최대])

| 시나리오 | 구성 | Failover(감지) | 첫 쓰기 실패 −T0 | 서비스 중단 / 쓰기 공백 | 장애 중 오류율 | 승인 쓰기 유실 | INCR 중복 | 복구 후 오류율 | 재합류 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Primary↔Sentinel 단절 | Sentinel | 5,539 ms 에 승격 | 없음 | 없음 (앱은 계속 옛 Primary 에 씀) | **0.00%** | **4,084 [0–4,120] (75.1%)** | 0 | 0.00% | 11,030 |
| Primary↔Replica 복제 단절 | Sentinel | 없음 | 없음 | 없음 | 0.00% | 0 | 0 | 0.00% | - |
| 앱↔Primary 만 단절 | Sentinel | 없음 | 2 ms | 52,337 / 52,330 (복구까지) | 99.05% | 0 | 295 | **30.46%** | 12,734 |
| 앱↔Primary 만 단절 | Cluster | 없음 | 27 ms | 52,610 / 52,545 | 35.22% | 0 | 96 | 11.28% | - |
| Primary↔다른 노드 단절 | Cluster | 6,697 | **6,403** | 12,247 / 5,722 | 7.50% | 151 (2.77%) | 0 | 0.00% | 1,206 |
| 전체 지연 200 ms | Sentinel / Cluster | 없음 | 없음 | 없음 | 0.00% (p95 202 ms) | 0 | 0 | 0.00% | 0.6 s |
| 손실 5 % | Sentinel / Cluster | 없음 | 없음 | 없음 | 0.00% (p95 1.95 / 10.2 ms) | 0 | 0 | 0.00% | - |

## 병목 원인·해석

**Primary↔Sentinel 단절 (Sentinel의 split-brain).** Sentinel 세 대는 Primary 와의 링크가 끊기자 5.5 s 에 `+odown`, 6.6 s 에 다른 Replica 를 승격하고 나머지 Replica 를 새 master 로 재구성했다. 그러나 앱↔옛 Primary, 옛 Primary↔Replica 링크는 멀쩡했고, 앱의 Lettuce 연결은 끊긴 적이 없어 `+switch-master` 를 알 방법이 없었다(EXP-A/B 의 pause 와 같은 원인). 앱은 40 초 동안 오류 0% 로 옛 Primary 에 계속 썼고, 파티션이 풀리자 Sentinel 이 옛 Primary 를 Replica 로 강등(`+convert-to-slave`, 11.0 s)하면서 그동안 승인된 4,084 건(장애 전후 승인 쓰기의 75%)이 폐기됐다. **오류율이 0 인데 데이터가 사라지는** 가장 위험한 형태다. 5회 중 1회는 유실 0 이었는데, 그 실행에서는 iptables 적용 전에 Sentinel 이 이미 다른 이유로… 가 아니라 승격 대상이 앱이 붙어 있던 노드와 같아진 경우다(재합류 표 참고). `min-replicas-to-write` 와 `readFrom=UPSTREAM` 변형은 EXP-SETTINGS/EXP-OPT 에서 잰다.

**Primary↔Replica 복제 단절.** Sentinel 은 Primary 를 볼 수 있으므로 아무 판단도 하지 않았다. Replica 만 `master_link_status:down` 이 됐고 앱은 영향이 없었다. 복제가 끊긴 상태에서 Primary 가 죽으면 EXP-F 의 "복제 지연" 상황과 같아진다.

**앱↔Primary 만 단절.** 감시자와 Replica 는 Primary 를 정상으로 보므로 Failover 가 없다(가설 확인). Sentinel 구성은 40 초 내내 99% 실패했고, Cluster 는 그 Primary 의 slot 만 실패해 35%였다. 더 눈에 띄는 것은 **복구 뒤**다: iptables 규칙을 지워도 Sentinel 구성은 30.5%, Cluster 는 11.3% 의 오류가 이어졌다. 블랙홀에 빠진 TCP 연결은 재전송 타이머가 지수적으로 늘어나(수십 초) 규칙이 풀린 뒤에도 곧바로 살아나지 않고, Lettuce 는 RST 를 받지 않아 재연결하지 않는다. 그 연결에 이미 써 둔 명령은 나중에 한꺼번에 서버에 도달해 실행됐다 — INCR 중복 295 / 96 건. 재연결이 아니라 **커널 TCP 재전송이 끝나기를 기다린** 결과라 `TCP_USER_TIMEOUT` 이 직접적인 해법이다(EXP-OPT).

**Primary↔다른 Cluster 노드 단절.** 고립된 Primary 는 6.4 s 동안 앱의 쓰기를 계속 받다가(첫 쓰기 실패 6,403 ms) `cluster-node-timeout` 이 지나 과반을 못 본다고 판단하면서 `CLUSTERDOWN` 을 돌려주기 시작했다 — Cluster 가 자체적으로 split-brain 을 닫는 지점이다. 나머지 노드는 6.7 s 에 FAIL, 7.5 s 에 Replica 를 승격했고 앱은 12.2 s 에 새 Primary 로 옮겨 갔다(쓰기 공백 5.7 s). 고립 구간 6.4 s 동안 승인된 151 건(2.8%)은 재합류 때 폐기됐다. Sentinel 의 75% 와 비교하면 창이 node-timeout 으로 제한된다는 것이 Cluster 의 구조적 이점이다.

**지연 200 ms.** 감지·승격이 아예 일어나지 않았고 p95 만 지연만큼 올라갔다(0.6 → 202 ms). 5 초 timeout 대비 200 ms 는 오탐을 만들지 않는다.

**손실 5 %.** 오류율 0, p95 는 재전송 때문에 Sentinel 2 ms, Cluster 10 ms(노드 간 왕복이 더 많음). Sentinel 구성의 "안정화 22 s" 는 실제 장애가 아니라 감시 프로브(400 ms timeout)가 손실에 흔들려 UNSTABLE/STABLE 이 반복된 것이다.

## 부작용

파티션 실험은 복구 후에도 TCP 재전송 지연 때문에 수십 초의 잔여 오류를 남긴다. 실험 사이의 안정 확인(쓰기 프로브 + 연결 리셋)이 없으면 다음 실험의 기준 구간이 오염된다(devlog #7).

## 결론

프로세스 종료는 "모두가 동시에 못 보는" 장애라 timeout 뒤 정해진 순서로 복구되지만, 파티션은 감시자·클라이언트·Replica 중 누가 못 보느냐에 따라 (1) Failover 는 됐는데 앱이 못 옮기는 경우(Sentinel 유실 75%), (2) Failover 없이 앱만 멈추는 경우(52 s), (3) 아무 일도 없는 경우로 갈렸다. Cluster 는 과반을 못 보는 Primary 가 스스로 쓰기를 멈춰 유실 창을 node-timeout 으로 제한한다.

## 다음 실험

EXP-OPT(TCP_USER_TIMEOUT, readFrom=UPSTREAM), EXP-SETTINGS(min-replicas-to-write 가 Sentinel 의 split-brain 창을 닫는지).

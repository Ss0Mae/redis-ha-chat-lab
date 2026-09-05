# EXP-SETTINGS. 데이터 안전성 설정 — min-replicas, WAIT, AOF 가 유실·지연·가용성에 미치는 영향

```text
실험 이름: settings/<설정>/<시나리오> — 복제 지연 500 ms + Primary SIGKILL(둘 다), Sentinel 은 pause·Primary↔Sentinel 단절에도 min-replicas 적용. 각 5회.
검증하려는 가설: (1) min-replicas-max-lag 는 초 단위라 500 ms 지연의 유실을 막지 못하지만, Replica 를 잃은 옛 Primary 의 쓰기(split-brain)는 막는다.
                (2) WAIT 1 은 승인 조건을 바꿔 유실을 0 으로 만들고 쓰기 p95 를 복제 왕복만큼 올린다.
                (3) AOF 는 승격 유실과 무관하고(복제 문제) appendfsync always 만 쓰기 p95 를 올린다.
변경한 항목: 실험 생성 시 CONFIG SET(min-replicas-to-write/max-lag, appendonly/appendfsync) 을 모든 노드에 적용, WAIT 는 앱 env LAB_WAIT_REPLICAS=1(쓰기 뒤 WAIT 1 1000).
통제한 조건·서버 사양·데이터·부하: EXP-F 와 같음.
```

## 측정 결과 (5회 중앙값 [최소–최대]; `docs/results.md` 3절)

### 복제 지연 500 ms 상태에서 Primary SIGKILL

| 구성 | 설정 | 승인 쓰기 유실 | 유실률 | 정상 시 쓰기 p95(ms) | 서비스 중단(ms) | 비고 |
|---|---|---:|---:|---:|---:|---|
| Sentinel | 기본(비동기) | 29 [25–41] | 0.42% | 0.68 | 6,732 | 기준 |
| Sentinel | min-replicas 1 / max-lag 1 | 32 [27–34] | 0.47% | 0.64 | 6,661 | 유실 그대로 (가설 1 확인) |
| Sentinel | WAIT 1 | **0** (5회 모두) | 0.00% | 0.68 [0.66–0.70] (지연 없을 때) / **약 950** (복제 지연 500 ms 상태) | 6,561 [6,530–6,751] | 유실 0 (가설 2 확인). INCR '중복' 1 [0–51] 은 WAIT 가 1 s 를 넘어 실패 처리됐지만 이미 반영된 쓰기 — 아래 참고 |
| Sentinel | AOF everysec | 30 [19–35] | 0.44% | 0.62 | 6,663 | 차이 없음 (가설 3 확인) |
| Sentinel | AOF always | 29 [20–36] | 0.42% | **1.62** [1.44–1.75] | 6,706 | p95 2.4배, 유실 그대로 (가설 3 확인) |
| Cluster | 기본(비동기) | 40 [30–47] | 0.57% | 0.46 | 9,607 | 기준(늦은 실행 포함, EXP-OPT) |
| Cluster | min-replicas 1 / max-lag 1 | 12 [10–20] | 0.18% | 0.46 | **40,195** | 유실 ↓, 그러나 승격 후 40 s 쓰기 거부 |
| Cluster | WAIT 1 | 7 [3–9] | 0.10% | 0.69 [0.64–0.72] (기본 0.46) | **40,183** | 유실 40 → 7 이지만 승격 후 40 s 쓰기 거부(Replica 0대인 새 Primary 에서 WAIT 1 이 안 채워짐), INCR '중복' 86 — 아래 참고 |
| Cluster | AOF everysec | 44 [37–53] | 0.66% | 0.48 | 10,060 | 차이 없음 |
| Cluster | AOF always | 44 [35–54] | 0.64% | **1.43** [1.30–1.49] | 11,462 | p95 3.1배, 유실 그대로 |

### Sentinel split-brain 장애에서 min-replicas-to-write 1 / max-lag 1

| 시나리오 | 설정 | 승인 쓰기 유실 | 서비스 중단 | 장애 중 오류율 | 해석 |
|---|---|---:|---:|---:|---|
| pause → unpause | 기본 | 1,310 (24.0%) | 40,180 | 98.2% | 복귀한 옛 Primary 가 10 s 동안 쓰기를 받음 |
| pause → unpause | min-replicas | **0** | 50,206 | 99.1% | 복귀한 옛 Primary 에 Replica 가 없어 NOREPLICAS 로 거부 → 유실 0, 대신 앱이 옮겨 갈 때까지 계속 실패 |
| Primary↔Sentinel 단절 | 기본 | 4,084 (75.1%) | 없음(오류 0%) | 0.0% | 조용한 유실 |
| Primary↔Sentinel 단절 | min-replicas | **73 [70–76]** (1.37%) | 50,890 | 37.0%(승격 후 쓰기 전부 NOREPLICAS) | Sentinel 이 Replica 를 새 master 로 돌린 뒤부터 옛 Primary 가 쓰기를 거부 |

## 병목 원인·해석

- **min-replicas-max-lag 는 REPLCONF ACK 의 "초" 를 본다.** 500 ms 지연에서는 lag 가 1 s 를 넘지 않아 쓰기가 계속 승인되고, 복제 창의 유실(Sentinel 29~32건)은 그대로다. 반면 옛 Primary 가 Replica 를 잃는 split-brain 상황에서는 즉시 `NOREPLICAS` 를 돌려 **조용한 유실(4,084)을 시끄러운 실패(73건 유실 + 37% 오류)** 로 바꿨다. 73 건은 Sentinel 이 Replica 를 새 master 로 재구성하기 전(승격 뒤 약 1 s) 옛 Primary 가 아직 Replica 를 갖고 있던 순간의 쓰기다. 정합성 우선이면 이 trade 가 맞고, 가용성 우선이면 아니다.
- **Cluster + min-replicas 1 은 Replica 1대 구성과 맞지 않는다.** 승격된 Replica 는 그 순간 Replica 가 0대라 자기 slot 의 쓰기를 40 s(옛 Primary 가 복구돼 Replica 로 붙을 때까지) 거부했다. Primary 당 Replica 가 2대 이상일 때만 쓸 수 있는 설정이다.
- **AOF 는 승격 유실과 무관하다(가설 3 확인).** everysec 은 p95 에 영향이 없고 always 는 2.4~3.1배 느려졌지만 유실 건수는 네 설정 모두 같은 범위였다. AOF 는 "그 노드가 재시작했을 때 자기 데이터를 갖고 있느냐" 의 문제이고(EXP-D/E 의 전량 유실을 막는 수단), Replica 에 도착하지 못한 쓰기와는 관계가 없다.
- **WAIT 1 은 유실을 0 으로 만들었다(가설 2 확인) — 대가는 지연과 "실패했지만 반영된 쓰기" 다.** Sentinel 5회 모두 승인 쓰기 유실 0(기본 29). 앱이 쓰기 뒤 `WAIT 1 1000` 을 호출하므로 Replica 1대가 ACK 한 쓰기만 승인되고, 승격된 Replica 에 없는 쓰기는 애초에 승인되지 않는다. 비용은 둘이다. (1) 지연: 복제가 정상일 때는 같은 호스트라 p95 0.68 ms 로 기본과 같았지만, 복제 지연 500 ms 를 건 10 s 동안(이 시나리오는 kill 10 s 전에 netem 을 먼저 건다) 쓰기 p95 가 약 950 ms 로 뛰었다(5회 모두 관측, WAIT 는 Replica 의 ACK 왕복을 기다린다). (2) 애매한 실패: 5회 중 2회는 그 대기가 1 s(Lettuce command timeout)를 넘어 546·280건이 TIMEOUT 으로 실패 처리됐는데, 그중 INCR 51·30건은 Primary 에 이미 반영된 뒤였다. 클라이언트에게는 실패지만 값은 올라가 있어 우리 지표에서 "중복" 으로 잡혔다 — **WAIT 실패는 "쓰기 안 됨" 이 아니라 "복제 확인 안 됨" 이다.** 이를 재시도하면 진짜 중복이 되므로 WAIT 와 재시도는 멱등 연산에서만 함께 쓸 수 있다. WAIT 는 호출한 연결을 막는 명령이라 Lettuce 의 공유 연결 하나에서는 뒤따르는 명령도 함께 기다린다(읽기 지연을 따로 재지는 않았다). 구현 메모: Spring 의 generic `execute("WAIT")` 는 정수 응답을 못 받아 Lettuce 네이티브 `waitForReplication` 으로 교체했다(devlog).
- **Cluster + WAIT 1 은 min-replicas 와 같은 절벽에 떨어진다.** 유실은 40 → 7 로 줄었지만(남은 7건은 DEFAULT 버퍼의 늦은 실행, EXP-OPT), 승격된 Replica 에는 Replica 가 없어 `WAIT 1 1000` 이 채워지지 않고 모든 쓰기가 1 s 뒤 TIMEOUT 으로 실패했다 — 옛 Primary 가 복구돼 Replica 로 붙는 40 s 동안(중단 40,183 ms, 장애 중 오류율 34.8 %, TIMEOUT 4,196). 그렇게 실패 처리된 INCR 86건은 이미 반영돼 있었다. 정상 시에도 Cluster 의 WAIT 는 slot 담당 노드로의 왕복을 하나 더 만들어 p95 가 0.46 → 0.69 ms 로 올랐다(Sentinel 은 같은 연결이라 차이 없음). Primary 당 Replica 2대 이상이 아니면 Cluster 에서 WAIT 1 은 쓸 수 없다.

## 부작용

`min-replicas-to-write` 는 Replica 링크가 잠깐 흔들려도 쓰기를 거부하므로(복제 단절 실험 C-1 에서는 즉시 NOREPLICAS) 가용성 비용이 크다. `appendfsync always` 는 모든 쓰기의 p95 를 1 ms 이상 올렸다.

## 결론

복제 창의 유실을 막는 설정은 `WAIT` 하나였다(Sentinel 29 → 0, 지연 상태에서 쓰기 p95 0.7 ms → 950 ms). `min-replicas-to-write` 는 초 단위 판정이라 ms 지연 유실은 못 막지만 split-brain 창을 "조용한 유실 → 시끄러운 오류" 로 바꿔 준다. AOF 는 재기동 시 데이터 보존용이지 Failover 유실 대책이 아니다. Cluster 에서는 WAIT 1 이 유실을 40 → 7 로 줄이는 대신 Replica 1대 구성 특성상 승격 후 40 s 쓰기 거부를 낳아, min-replicas 와 마찬가지로 Replica 2대 이상에서만 쓸 수 있다.

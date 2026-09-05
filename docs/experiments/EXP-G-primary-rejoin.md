# EXP-G. 옛 Primary 복구 — 어떤 역할로, 어떻게 다시 합류하는가

```text
실험 이름: 모든 faults/* 실행의 복구 구간(장애 40 s 뒤 RESTORE_ALL) — Sentinel 65회, Cluster 55회
검증하려는 가설: 옛 Primary 는 Replica 로 합류한다. Sentinel 은 +convert-to-slave, Cluster 는 더 높은 configEpoch 를 보고 스스로 강등한다.
                분기된 쓰기(장애 중 옛 Primary 가 승인한 것)는 full resync 로 폐기된다.
변경한 항목: 없음(복구 방식은 docker start / unpause / iptables 삭제로 고정).
측정 항목: 재합류 시간 = 복구 시작 → 다음 STABLE(옛 Primary 가 REPLICA|UP 이고 링크 up). 재동기화 종류는 Redis 로그(Partial/Full resync). 복구 후 40 s 오류율.
```

## 측정 결과 (5회 중앙값 [최소–최대], ms)

| 장애 | Sentinel 재합류 | Cluster 재합류 | 재동기화 | 복구 후 오류율 (S / C) |
|---|---:|---:|---|---:|
| SIGKILL | 10,505 [10,236–10,620] | 501 [289–565] | full (재기동으로 replid 변경) | 0.00% [0–19.4] / 0.00% |
| SIGTERM | 10,311 [10,173–11,034] | 355 [259–498] | full | 0.00% / 0.00% |
| pause → unpause | 10,146 [10,136–10,189] | 259 [255–278] | full (분기 쓰기 폐기) | 1.01% / 0.00% |
| Primary↔Sentinel 단절 해제 | 11,030 [10,847–11,191] | - | full | 0.00% |
| Primary↔노드 단절 해제 (Cluster) | - | 1,206 [1,079–1,297] | partial 거부 → full | 0.00% [0–0.8] |
| 앱↔Primary 단절 해제 | 12,734 | -(역할 변화 없음) | - | 30.46% / 11.28% |
| Replica 1대 + Primary 재기동 | 22,290 | 2,293 | full | 0.00% / 4.77% |
| Replica 전부 + Primary 재기동 | 3,396 [2,609–28,861] | - | full | 8.66% |
| Sentinel 2/3 + Primary 재기동 | 21,461 | - | 빈 master 로 복귀 | 7.27% |
| Primary 2/3 재기동 (Cluster) | - | 21,230 [5,339–21,490] | full | 9.64% |
| 복제 지연 + SIGKILL | 10,321 | 496 | full | 0.00% / 0.00% |

## 해석

- **Sentinel 의 10 초.** 재기동한 옛 Primary 는 자기 설정대로 master 로 뜬다(설정 파일에는 강등 전 상태가 남아 있음). Sentinel 은 그 노드를 "master 역할을 보고하는 replica" 로 보고 `+role-change`/`+convert-to-slave` 를 보내는데, 여기에 고정된 약 10 초의 유예가 있다. 5회 편차가 ±200 ms 로 매우 작아 이 값은 Sentinel 의 내부 주기다. 그 10 초 동안 앱이 거기에 붙어 있으면 쓰기를 받아 준다 — pause 사례에서 1,310 건 유실의 원인.
- **Cluster 의 0.3~0.5 초.** 재기동한 노드는 `nodes.conf` 로 자기가 master 였다고 믿고 뜨지만, gossip 에서 같은 slot 을 더 높은 configEpoch 로 가진 노드를 보는 즉시 `Configuration change detected. Reconfiguring myself as a replica` 로 강등한다. 사람이 개입할 여지도, 쓰기를 받을 창도 거의 없다.
- **재동기화는 전부 full 이었다.** 재기동한 프로세스는 이전 replication id 를 잃고, pause/파티션처럼 프로세스가 살아 있던 경우에도 옛 Primary 의 offset 이 새 master 의 분기점을 넘어(승인해 버린 쓰기) `Partial resynchronization not accepted: Replication ID mismatch` 가 났다. 데이터가 256 MB 이하라 full resync 자체는 수백 ms 였다.
- **복구 뒤 오류가 이어지는 경우**는 두 부류다. (1) 앱↔Primary 파티션: 블랙홀에 빠진 TCP 연결의 재전송 지연(EXP-C). (2) 여러 노드가 순서대로 재기동하는 시나리오: full resync 중 잠깐의 READONLY/CLUSTERDOWN 과 늦게 실행된 옛 명령.
- **설정을 영속화하기 전(ADR-006, `results/faults-immutable/`)** 에는 같은 시나리오의 재합류가 300 ms 에서 21 s 까지 갈렸고, 승격됐던 노드가 재기동하며 부트스트랩 역할로 되돌아가 세 노드가 서로의 Replica 가 되는 순환 상태(master 없음)까지 나왔다. 현재 표는 영속화 이후 값이다.

## 결론

옛 Primary 는 두 구성 모두 Replica 로 합류하며(가설 확인) 분기된 쓰기는 폐기된다. 합류 속도는 Cluster(0.5 s) 가 Sentinel(10 s) 보다 20 배 빠르고, Sentinel 의 그 10 초는 앱 연결이 옛 노드에 남아 있을 때 데이터 유실 창이 된다. 재기동한 노드가 이전 역할을 기억하려면 설정 파일이 쓰기 가능해야 한다.

## 다음 실험

EXP-TIMING: `master-reboot-down-after-period 10 s` — 빈 상태로 재부팅한 master 를 down 으로 취급해 다른 Replica 를 승격하는지(EXP-E 의 47% 유실 대응).

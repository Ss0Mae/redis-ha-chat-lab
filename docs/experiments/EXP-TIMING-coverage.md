# EXP-TIMING / EXP-COVERAGE. 감지 시간 설정 민감도, 재부팅 보호, require-full-coverage

```text
실험 이름: timing/<설정>/KILL_PRIMARY, timing/reboot-down-10s/STOP_SENTINELS_THEN_KILL_PRIMARY, coverage/full-coverage-no/STOP_PRIMARY_AND_ITS_REPLICA — 각 5회
검증하려는 가설: (1) 중단 시간은 down-after / node-timeout 에 거의 선형이다. 1 s 로 줄이면 오탐(정상 시 오류)이 생길 수 있다.
                (2) master-reboot-down-after-period 10 s 는 빈 상태로 재부팅한 Primary 를 잠시 down 으로 취급해 Replica 승격을 유도, 전량 유실을 막는다.
                (3) require-full-coverage no 는 slot 공백을 해당 slot 로 격리한다.
변경한 항목: Sentinel/Cluster 컨테이너 재생성 시 설정값만 변경(SENTINEL_DOWN_AFTER, SENTINEL_REBOOT_DOWN, CLUSTER_NODE_TIMEOUT, CLUSTER_FULL_COVERAGE).
통제한 조건·서버 사양·데이터·부하: EXP-A/B 와 같음.
```

## 측정 결과 (5회 중앙값 [최소–최대]; `docs/results.md` 5·6절)

| 구성 | 설정 | 시나리오 | 감지(ms) | 서비스 중단(ms) | 장애 중 오류율 | 정상 시 오류율(오탐) | 승인 쓰기 유실 |
|---|---|---|---:|---:|---:|---:|---:|
| Sentinel | down-after 1,000 | SIGKILL | 995 [958–1,014] | **2,159** [2,142–2,172] | 3.2% | 0.00% | 0 |
| Sentinel | down-after 5,000 (기본) | SIGKILL | 5,008 | 6,566 | 13.6% | 0.00% | 0 |
| Sentinel | down-after 15,000 | SIGKILL | 15,000 | 19,976 [15,864–21,064] | 46.6% | 0.00% | 0 |
| Cluster | node-timeout 1,000 | SIGKILL | 1,307 [1,247–1,525] | **4,332** [2,817–4,705] | 5.1% | 0.00% | 17 |
| Cluster | node-timeout 5,000 (기본) | SIGKILL | 7,114 | 12,032 | 11.8% | 0.00% | 35 |
| Cluster | node-timeout 15,000 | SIGKILL | 21,727 [17,408–22,895] | **25,151** [18,529–27,672] | 23.9% | 0.00% | 40 |
| Sentinel | reboot-down 10 s | SIGKILL | 5,022 | 6,697 | 14.2% | 0.00% | 0 |
| Sentinel | reboot-down 10 s | Sentinel 2/3 중지 후 Primary 종료 | 없음 | 43,316 | 99.6% | 0.00% | **3,486** (기본 2,790) |
| Cluster | full-coverage yes (기본) | Primary+Replica 동시 종료 | 6,665 | 46,461 | **88.6%** (성공 0건 33 s) | 0.00% | 496 |
| Cluster | full-coverage no | Primary+Replica 동시 종료 | 5,555~6,922 | 46,370 | **35.5%** [34.7–36.1] (성공 0건 0 s) | 0.00% | 905 |

node-timeout 15,000 은 1차 측정 3회가 호스트 절전에 겹쳐 격리했고(devlog #13), 재측정 3회를 더한 5회 값이다.

## 해석

- **가설 (1) 확인.** Sentinel 중단 = down-after + 약 1.2~1.6 s(선출·승격·재연결)로 1 s 설정에서 2.2 s, 15 s 에서 20 s. Cluster 중단 = node-timeout × 약 1.3(gossip) + 선거 0.8 s + 갱신 2~3 s 로 1 s 설정에서 4.3 s, 15 s 에서 25 s(감지 21.7 s = node-timeout × 1.45). 정상 구간 오류율은 세 설정 모두 0 이었다 — 같은 VM 안의 지연이 ms 단위라 1 s 로 줄여도 오탐이 없었다. 실제 네트워크에서는 1 s 가 오탐(불필요한 Failover)을 만들 수 있으므로, 여기서는 "이 환경에서 오탐 없이 얻을 수 있는 하한" 으로만 해석한다. 장애 중 오류율은 중단 시간에 비례했다(Sentinel 3.2 → 13.6 → 46.6%).
- **가설 (2) 는 기각.** `master-reboot-down-after-period 10 s` 를 줘도 quorum 부족 상태에서 Primary 가 죽었다 빈 상태로 돌아오면 유실은 3,486 건으로 오히려 기본(2,790)보다 컸다. Sentinel 이 재부팅한 master 를 10 초 동안 down 으로 취급해 Failover 를 시도하는 사이, **Replica 들은 이미 빈 master 와 재연결해 full resync 로 자기 데이터를 지운 뒤**였다. 그 뒤 승격되는 Replica 도 비어 있다. 이 설정은 "Replica 가 아직 옛 데이터를 들고 있는" 짧은 순간에만 의미가 있고, 진짜 대책은 Primary 의 영속화(AOF)나 `replica-serve-stale-data` 같은 다른 축이다. 단순 SIGKILL(quorum 있음)에서는 아무 차이가 없었다(6.7 s, 유실 0).
- **가설 (3) 확인.** `require-full-coverage no` 는 오류율을 88.6% → 35.5% 로 낮추고 "성공 0건인 초" 를 33 s → 0 으로 만들었다. 해당 slot(1/3)의 요청만 실패하고 나머지는 정상이었다. 다만 그 slot 의 데이터는 어차피 복구 때 빈 노드로 돌아오므로 유실(905)은 같거나 크다. 가용성과 "부분 결손 상태로 서비스하는 위험" 을 맞바꾸는 설정이다.

## 결론

감지 시간 설정이 중단 시간의 첫 번째 결정 요인이다(1 s → 2.2/4.3 s). 재부팅 보호 설정은 비영속 재기동의 데이터 손실을 막지 못했고, slot 공백에서는 `require-full-coverage` 가 전체 장애와 부분 장애를 가른다.

# 실험 문서 목록

모든 수치는 실측값이며 표의 값은 5회 반복 중앙값 [최소–최대] 이다. 원시 데이터는 `results/<세트>/raw/`, 자동 집계 표·그래프는 `docs/results.md` 와 `docs/charts/`, 실행별 타임라인은 `docs/timelines/`.

| 문서 | 스펙 항목 | 내용 |
|---|---|---|
| [EXP-AB-primary-termination](EXP-AB-primary-termination.md) | 실험 A·B | SIGTERM / SIGKILL / pause 로 Primary 를 끊었을 때 T0~T6, 오류율, 유실·중복 |
| [EXP-C-network-partition](EXP-C-network-partition.md) | 실험 C | Primary↔Sentinel / Replica / 앱 / 다른 노드 단절, 지연 200 ms, 손실 5 % |
| [EXP-DE-replica-and-quorum](EXP-DE-replica-and-quorum.md) | 실험 D·E | Replica 선행 장애, Sentinel·Primary 과반 상실, Sentinel 전부 중지 |
| [EXP-F-replication-lag-loss](EXP-F-replication-lag-loss.md) | 실험 F, 9절 | 복제 지연 500 ms 상태의 Primary 종료와 승인 쓰기 유실 |
| [EXP-G-primary-rejoin](EXP-G-primary-rejoin.md) | 실험 G | 옛 Primary 재합류 시간·역할·재동기화 종류 |
| [EXP-H-slot-gap](EXP-H-slot-gap.md) | 실험 H | Primary + Replica 동시 종료, `require-full-coverage` |
| [EXP-CROSSSLOT-hash-tag](EXP-CROSSSLOT-hash-tag.md) | 5절·13절 | Hash Tag 적용 전후 CROSSSLOT, MGET |
| [EXP-SETTINGS-data-safety](EXP-SETTINGS-data-safety.md) | 9절 | `min-replicas-to-write`, `WAIT`, AOF 설정별 유실·p95·가용성 |
| [EXP-OPT-lettuce-options](EXP-OPT-lettuce-options.md) | 6절 | Lettuce 토폴로지 갱신·재연결·재전송·타임아웃·TCP_USER_TIMEOUT·readFrom·풀 |
| [EXP-TIMING-coverage](EXP-TIMING-coverage.md) | 3절·실험 H | down-after / node-timeout 1 s·5 s·15 s, master-reboot-down-after-period, require-full-coverage |
| [EXP-PERF-load-stages](EXP-PERF-load-stages.md) | 11절 | k6 100/500/1,000/2,000 RPS, 정상·장애·복구 구간 성능, 스레드 풀 병목 |
| [EXP-MISC-recording-and-toxiproxy](EXP-MISC-recording-and-toxiproxy.md) | 15절, 6절 | 기록 방식(MEMORY/BATCH/SAMPLED) 부하 영향, 앱↔Sentinel Toxiproxy |

공통 조건(모든 faults/* 실행): 앱 이미지 동일, Lettuce command timeout 1 s, Sentinel down-after 5 s / Cluster node-timeout 5 s, 300 RPS(GET50/SET20/INCR10/SEND15/MGET5), 사용자 10,000·방 100, 워밍업 10 s → 정상 30 s → 장애 40 s → 복구 40 s, 실험마다 FLUSHALL 과 토폴로지 안정·쓰기 프로브 확인, 시나리오 순서를 반복마다 그대로 두되 대상 Primary 는 직전 실험의 승격 결과에 따라 바뀜(노드 편향 방지).

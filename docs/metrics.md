# 측정 지표와 메트릭

## 시각 정의와 계산식

| 기호 | 정의 | 어디서 읽는가 |
|---|---|---|
| T0 | 장애 주입 시각 | 앱: Docker API(stop/kill/pause) 또는 사이드카 exec 가 반환된 직후 `Instant.now()` |
| T1 | Redis 측이 장애를 확정한 시각 | Sentinel: pubsub `+odown`. Cluster: 생존 노드 로그 `Marking node … as failing (quorum reached)`(1차) / 워처의 `fail` 플래그(2차) |
| T2 | 승격 절차 시작 | Sentinel: `+try-failover`. Cluster: Replica 로그 `Start of election delayed`(선거 대기 시작) |
| T3 | 새 Primary 승격 완료 | Sentinel: `+promoted-slave`/워처 role 변경 중 빠른 것, `+switch-master` 는 별도 기록. Cluster: `Failover election won` |
| T4 | 앱이 새 Primary 에 연결 | Lettuce EventBus `ConnectedEvent`(원격 주소 = 새 Primary) / Cluster `ClusterTopologyChangedEvent` |
| T5 | 앱의 첫 쓰기 성공 | 워크로드 결과 중 장애 shard 를 향한 첫 OK 쓰기의 `ackedAt` |
| T6 | 토폴로지 안정 | 워처 `STABLE` 전이: Primary 1개, 모든 살아 있는 Replica 링크 up, (Cluster) 전 노드 뷰 일치 + `cluster_state:ok` |

```text
장애 감지 시간      = T1 − T0
Replica 승격 시간    = T3 − T2
클라이언트 인식      = T4 − T3
클라이언트 재연결    = T5 − T3
서비스 중단 시간     = T5 − T0
토폴로지 안정화 시간 = T6 − T0
재합류 시간          = 복구 시작 → 다음 STABLE (옛 Primary 가 Replica 로 붙고 링크 up)
```

폴링 기반 값(워처 200 ms)은 로그 시각(같은 VM 시계)으로 보정한다. `bench/collect.py` 가 Cluster 의 T1~T3 를 로그 기준으로 바꾸고 원래 값은 `timings_watcher` 로 남긴다.

## 정합성 계산

```text
승인된 쓰기 수(A)             = 장애 전 responseStatus=OK 인 쓰기 (WAIT 사용 시 WAIT 가 채워진 것만)
복구 후 존재하는 승인 쓰기(B)  = 스트림/키에 남은 A
승인 후 유실(L)               = 메시지 유실 + INCR 유실 + 잘못된 값 키
마지막 보존 sequenceNumber    = max(B)
중복 반영된 INCR              = 최종 unread 값 − ACK 된 INCR 수 (양수만)
잘못된 값으로 복구된 키        = presence 값 < 마지막 ACK seq (또는 없음)
승인 쓰기 유실률(%)           = L / A × 100
```

주의: "잘못된 값" 에는 두 원인이 섞인다. (1) 비동기 복제로 승격된 Replica 에 없던 쓰기, (2) 타임아웃으로 실패 처리된 옛 쓰기가 재연결 후 늦게 실행돼 더 새로운 값을 덮어쓴 경우. `disconnectedBehavior=REJECT_COMMANDS` 실험과 비교하면 (2)를 분리할 수 있다.

## Micrometer 메트릭

| 메트릭 | 타입 | 태그 | 이유 |
|---|---|---|---|
| `redis_commands_total` | Counter | op, outcome | 누적 건수. `rate()` 로 초당·성공률 |
| `redis_command_duration_seconds` | Timer(히스토그램) | op | `histogram_quantile` 로 p95/p99 |
| `redis_command_failures_total` | Counter | op, error(TIMEOUT/CONNECTION/MOVED/ASK/CROSSSLOT/CLUSTERDOWN/READONLY/NOREPLICAS/WAIT_TIMEOUT/OTHER) | 오류 종류별 |
| `redis_connection_failures_total` | Counter | — | Lettuce `ReconnectFailedEvent` |
| `redis_reconnect_total` | Counter | — | `ReconnectAttemptEvent` |
| `redis_topology_refresh_total` | Counter | — | `ClusterTopologyChangedEvent` |
| `redis_connection_events_total` | Counter | type=connected/disconnected | 연결 수명 |
| `redis_failover_total` | Counter | topology, scenario | 장애 주입 횟수 |
| `redis_failover_duration_seconds` | Timer | topology, phase=detect/promote/total | T 차이. 실험당 1회 기록이라 `_max` 를 본다 |
| `redis_write_ack_total`, `redis_acknowledged_write_loss_total`, `redis_duplicate_operation_total` | Counter | — | 정합성 검증 결과(실험 종료 시 증가) |
| `redis_replication_lag_bytes` | Gauge | node | Primary offset − Replica offset (워처 200 ms) |
| `redis_node_up` | Gauge | node, role | 0/1 |
| `redis_cluster_redirect_total` | Counter | type=MOVED/ASK | 앱이 받은 redirect 오류 수 |
| `redis_retry_total` | Counter | op | 앱 레벨 재시도 |
| `workload_requests_total`, `workload_request_duration_seconds` | Counter / Timer | op, outcome | HTTP·내부 워크로드 관점 |

Counter 는 "몇 번 일어났는가", Timer 는 "얼마나 걸렸는가의 분포", Gauge 는 "지금 값" 이라 그렇게 나눴다. Failover 시간은 실험당 한 번 기록되는 값이라 Timer 의 `_max`/`_sum` 을 쓴다.

## Prometheus / Grafana

- Prometheus 1 s 스크레이프: `lab-app:8080/actuator/prometheus`, `redis_exporter` 멀티 타겟(`/scrape?target=redis://IP:6379`, 노드 10개).
- Grafana 대시보드 `docker/grafana/dashboards/redis-ha-lab.json` (uid `redis-ha-lab`, http://localhost:3003/d/redis-ha-lab): 노드 상태, 현재 Primary, 초당 명령, 성공률·오류율, p95·p99, Failover 단계(bargauge), 복제 지연(앱·exporter), 연결 실패·재연결·토폴로지 갱신, 승인 쓰기·중복·유실, 오류 종류별, MOVED/ASK, 워크로드 p95/p99, 노드 메모리, 장애 전·중·후 처리량. `redis_failover_total` 변화가 annotation 으로 찍힌다.

## 기록 방식 (MySQL, 실험 기록 전용)

`experiment`(T0~T6, 요약 JSON), `experiment_event`(타임라인), `request_log`(BATCH/SAMPLED 모드 요청 행), `metric_sample`(초별 집계), `fault_action`(장애 주입 이력). 요청 경로는 DB 를 기다리지 않는다(ADR-005).

# EXP-D/E. Replica 선행 장애, Quorum 부족 — 자동 복구가 불가능해지는 조건

```text
실험 이름: faults/KILL_REPLICA_THEN_PRIMARY, KILL_ALL_REPLICAS_THEN_PRIMARY (Sentinel), KILL_REPLICA_THEN_PRIMARY (Cluster),
          STOP_SENTINELS_THEN_KILL_PRIMARY, STOP_ALL_SENTINELS (Sentinel), KILL_TWO_PRIMARIES (Cluster) — 각 5회
검증하려는 가설: (D) Replica 가 하나라도 남으면 승격되고, 전부 없으면 복구까지 전면 중단이다. Cluster 에서 담당 Replica 가 없는 Primary 가 죽으면
                require-full-coverage=yes 때문에 전체가 멈춘다. (E) Sentinel 과반이 없으면 ODOWN·Failover 가 없고, Redis 가 살아 있으면 서비스는 계속된다.
                Cluster 는 Primary 과반이 없으면 선거도 쓰기도 불가하다.
변경한 항목: 선행 장애(Replica kill / Sentinel 2대 stop / Primary 1대 kill) 3 s 뒤 Primary kill. 복합 시나리오의 T0 는 마지막 단계(Primary kill) 시각.
통제한 조건·서버 사양·데이터·부하: EXP-A/B 와 같음. 복구는 죽인 컨테이너 전부 start(비영속 설정이라 빈 상태로 기동).
```

## 측정 결과 (5회 중앙값 [최소–최대])

| 시나리오 | 구성 | 감지 | 승격 | 서비스 중단 | 장애 중 오류율 | 성공 0건 초 | 승인 쓰기 유실 | INCR 중복 | 복구 후 오류율 | 재합류 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Replica 1대 선행 장애 후 Primary 종료 | Sentinel | 4,951 | 419 | **6,546** | 13.6% | 5 s | 0 | 0 | 0.00% | 22,290 |
| Replica 1대 선행 장애 후 Primary 종료 | Cluster | 4,412 | 없음 | **47,553** (복구까지) | 87.5% | 33 s | 671 (11.2%) | 140 | 4.77% | 2,293 |
| Replica 전부 선행 장애 후 Primary 종료 | Sentinel | 5,002 | 37,603 (복구 뒤) | **46,622** | 99.7% | 43 s | 2,726 (46.1%) | 147 | 8.66% | 3,396 [2,609–28,861] |
| Sentinel 2/3 중지 후 Primary 종료 | Sentinel | 없음(ODOWN 불가) | 없음 | **43,967** | 99.9% | 41 s | 2,790 (47.2%) | 131 | 7.27% | 21,461 |
| Sentinel 전부 중지(Redis 정상) | Sentinel | - | - | 없음 | **0.00%** | 0 | 0 | 0 | 0.00% | 448 |
| Primary 2/3 종료(과반 상실) | Cluster | 없음 | 없음 | **46,530** | 94.1% | 35 s | 1,524 (27.6%) | 184 | 9.64% | 21,230 [5,339–21,490] |

## 병목 원인·해석

**D-Sentinel, Replica 1대.** 남은 Replica 하나가 그대로 승격돼 EXP-A 와 같은 6.5 s. 유실 0. 재합류만 22 s 로 길어졌는데 두 노드가 함께 재기동하며 순서대로 `+convert-to-slave`(각 10 s)를 받기 때문이다.

**D-Cluster, Replica 1대.** Primary 당 Replica 가 하나뿐인 3P+3R 에서 그 하나를 먼저 죽이면 해당 shard 는 승격 후보가 없다. 4.4 s 에 FAIL 은 났지만 선거가 없고, `cluster-require-full-coverage yes` 라 **다른 두 shard 의 정상 요청까지 `CLUSTERDOWN`** 으로 거부돼 오류율 87.5%, 성공 0건이 33 초였다. 복구 때 두 노드가 빈 상태로 재기동하면서 그 shard 의 데이터가 사라졌다(유실 671 = 복구 구간의 재쓰기로 덮이지 않은 키). Cluster 의 "부분 장애 격리" 는 Replica 가 남아 있을 때만 성립하고, Replica 가 없는 shard 는 설정에 따라 전체 장애로 번진다(EXP-H 에서 `require-full-coverage no` 와 비교).

**D-Sentinel, Replica 전부.** Sentinel 은 5 s 에 ODOWN 뒤 `+try-failover` 까지 갔지만 `+failover-state-select-slave` 에서 고를 Replica 가 없어 `failover-timeout`(20 s) 마다 재시도했다. 복구로 Replica 들이 (빈 상태로) 돌아오자 그중 하나를 승격했고(T2 로부터 37.6 s), 옛 Primary 도 빈 채로 돌아와 Replica 로 합류했다. 결과는 장애 전 승인 쓰기의 46% 유실 — 실제로는 복구 구간에 다시 쓰인 키를 제외한 "전량" 이다. **Replica 없는 Primary 의 데이터는 그 프로세스가 죽는 순간 사라진다**(RDB/AOF 를 끈 실험 조건). 재합류 범위 2.6~28.9 s 는 세 노드의 재기동·강등 순서 차이다.

**E-Sentinel, 2/3 중지.** 남은 Sentinel 한 대는 `+sdown` 까지만 하고 quorum 2 를 못 채워 ODOWN·Failover 가 없었다(가설 확인). Primary 가 죽어 있는 44 s 동안 앱은 99.9% 실패. 복구 뒤에도 옛 Primary 가 빈 채로 master 로 돌아오고 Sentinel 은 그것을 그대로 master 로 인정(영속화된 설정에 master = 그 노드)해 Replica 들이 빈 master 를 full resync — 47% 유실. `master-reboot-down-after-period`(EXP-TIMING) 는 정확히 이 상황을 위한 설정이다: 재부팅한 master 를 잠시 down 으로 취급해 다른 Replica 를 승격할 기회를 준다.

**E-Sentinel, 전부 중지.** Redis 가 정상이면 Sentinel 이 전부 죽어도 앱은 영향이 없었다(오류 0%). Sentinel 은 데이터 경로에 없다는 것의 직접 증거다. 단 이 상태에서 Primary 가 죽으면 위 행이 된다.

**E-Cluster, Primary 2/3.** 남은 Primary 한 대도 과반을 못 보므로 `cluster_state:fail`, 살아 있는 Replica 들은 선거에 필요한 과반 표를 못 얻는다. 전 slot 이 46.5 s 동안 거부됐고(94%), 죽은 두 Primary 가 빈 채로 돌아와 그 데이터가 사라졌다(27.6%). 재합류 21 s 는 두 노드가 순서대로 epoch 를 맞추고 replica 들이 빈 master 를 다시 동기화하는 시간이다.

## 부작용

- 비영속 설정(RDB/AOF off)에서 "복구" 는 곧 "빈 노드" 다. 이 실험의 유실 수치는 복제가 아니라 영속화 부재를 재는 셈이라, 영속화 설정 비교(EXP-SETTINGS 의 AOF 변형)와 함께 읽어야 한다.
- 복구 뒤 4~10% 의 오류가 이어진다: 재기동한 노드가 full resync 하는 동안 READONLY/CLUSTERDOWN 이 잠깐 나고, 파티션이 아닌데도 앱이 옛 연결로 보낸 명령이 늦게 실행돼 INCR 중복 130~180 건이 생겼다.

## 결론

두 구성 모두 "승격할 Replica" 와 "판단할 과반" 이 있어야 자동 복구가 된다. Sentinel 은 Sentinel 과반(3대 중 2대), Cluster 는 Primary 과반(3대 중 2대)이 필요하고, 부족하면 사람이 올 때까지 멈춘다. 다른 점은 멈추는 범위다: Sentinel 은 어차피 Primary 하나라 전면 중단이고, Cluster 는 `require-full-coverage` 설정이 "해당 shard 만" 과 "전체" 를 가른다.

## 다음 실험

EXP-H(`require-full-coverage no`), EXP-TIMING(`master-reboot-down-after-period 10 s`), EXP-SETTINGS(AOF 로 재기동 후 데이터 보존).

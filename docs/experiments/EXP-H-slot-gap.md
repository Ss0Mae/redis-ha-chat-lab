# EXP-H. Cluster 일부 slot 장애 — Primary 와 Replica 를 함께 잃었을 때

```text
실험 이름: faults/STOP_PRIMARY_AND_ITS_REPLICA (Cluster, require-full-coverage yes, 5회) + coverage/full-coverage-no (5회, EXP-TIMING 문서의 coverage 절)
검증하려는 가설: slot 0–5460 이 비면 require-full-coverage=yes 에서는 전체 Cluster 가 CLUSTERDOWN 을 돌려주고, no 에서는 그 slot 만 실패한다.
변경한 항목: slot 0–5460 담당 Primary 와 그 Replica 를 동시에 SIGKILL.
통제한 조건·서버 사양·데이터·부하: EXP-A/B 와 같음.
```

## 측정 결과 (5회 중앙값 [최소–최대])

| 설정 | 감지(FAIL) | 서비스 중단 | 장애 중 오류율 | 성공 0건 초 | 오류 종류(회당) | 승인 쓰기 유실 | INCR 중복 | 복구 후 오류율 | 재합류 |
|---|---:|---:|---:|---:|---|---:|---:|---:|---:|
| `require-full-coverage yes` (기본) | 6,665 | **46,461** (복구까지) | **88.6%** [86.5–89.4] | 33 s | CLUSTERDOWN 6,517 + TIMEOUT 4,487 | 496 (9.2%) | 206 | 4.26% | 3,365 |
| `require-full-coverage no` | 5,555~6,922 | 46,370 (복구까지) | **35.5%** [34.7–36.1] | **0 s** | TIMEOUT 4,489 (해당 slot 만) | 905 (8.5%) | 47 | 0.00% | 2,297 |

## 해석

- 두 노드가 죽은 뒤 6.7 s 에 FAIL 이 확정됐지만 그 slot 을 넘겨받을 노드가 없다. `cluster-require-full-coverage yes` 에서는 16,384 slot 중 하나라도 비면 **모든 노드가 모든 키 요청에 `CLUSTERDOWN Hash slot not served` 를 돌려준다.** 영향받지 않아야 할 slot 5461–16383 의 요청까지 실패해 오류율 88.6%, 33 초 동안 성공 0 건이었다(TIMEOUT 은 죽은 노드로 향한 요청, CLUSTERDOWN 은 살아 있는 노드가 거부한 요청).
- 복구로 두 노드가 (빈 상태로) 돌아오면 `cluster_state:ok` 가 되지만 그 shard 의 데이터는 사라졌다(유실 496, 복구 구간 재쓰기로 덮인 키 제외). 재합류 3.4 s 는 두 노드가 gossip 으로 epoch 를 맞추고 Replica 가 빈 master 를 동기화하는 시간이다.
- "Primary 한 대당 Replica 한 대" 는 정확히 한 번의 장애를 견디는 구성이다. 같은 shard 의 두 노드가 같은 장비·같은 AZ 에 있으면 이 시나리오는 흔하다.

## 결론

`require-full-coverage no` 로 바꾸자 같은 장애에서 오류율이 88.6% → 35.5%, "성공 0건인 초" 가 33 s → 0 이 됐다(5회 모두 34.7~36.1%). 해당 slot(1/3)의 요청만 실패하고 나머지 두 shard 는 장애 내내 정상이었다 — 부분 장애 격리는 이 설정에서만 성립한다. 기본값 `yes` 는 정합성 관점에서 안전한 선택(일부 키가 조용히 사라진 채 서비스되는 것을 막음)이지만 가용성 관점에서는 전면 장애다. 어느 쪽이 맞는지는 업무가 정한다: 채팅에서 일부 방이 안 열리는 것과 전체가 멈추는 것 중 무엇이 나은가. 두 설정 모두 그 shard 의 데이터는 복구 때 빈 노드로 돌아와 사라지므로(유실 496 / 905), 영속화 없이는 Replica 1대 구성이 견디는 장애는 정확히 한 번이다.

# 면접 질문과 실측 근거 답변

모든 수치는 이 저장소에서 직접 측정한 값(300 RPS, 5회 중앙값)이다. 출처는 각 답의 링크.

### Q1. Sentinel 과 Cluster 는 각각 어떤 문제를 푸나요?

Sentinel 은 "Primary 한 대가 죽었을 때 누가 승격을 결정하는가" 를 푼다. 데이터는 여전히 한 Primary 에 다 있다. Cluster 는 그 위에 "데이터와 쓰기를 여러 Primary 로 나누는 것" 을 더한다. 실측에서 이 차이는 장애 범위로 나타났다: Primary SIGKILL 때 Sentinel 은 6 초 동안 성공 요청이 0 건이었고, Cluster 는 키의 1/3 만 영향받아 "성공 0건인 초" 가 없었다(오류율은 13.6 % 와 11.8 % 로 비슷). → [EXP-AB](experiments/EXP-AB-primary-termination.md)

### Q2. Sentinel Failover 는 실제로 몇 초 걸렸나요? 어디서 시간이 가나요?

6.6 초. 감지 5.0 s(`down-after-milliseconds 5000` 그대로) + 승격 0.3 s + 클라이언트가 새 Primary 에 첫 쓰기를 성공하기까지 1.2 s. 감지 설정이 지배한다: 1 s 로 줄이면 2.2 s, 15 s 면 20 s. 같은 VM 안이라 1 s 에서도 오탐이 없었지만 실제 네트워크에서는 그렇지 않을 수 있다. → [EXP-TIMING](experiments/EXP-TIMING-coverage.md)

### Q3. Cluster 는 왜 같은 5 초 설정에서 12 초가 걸렸나요?

세 군데서 더 든다. (1) FAIL 확정이 gossip 이다: 각 노드가 PFAIL 을 매기고 Primary 과반이 그 보고를 모아야 FAIL 이 되므로 5 s 설정에서 7.1 s. (2) Replica 선거는 `Start of election delayed` 뒤 500 ms + 랜덤 지연 뒤에 시작해 0.8 s. (3) 클라이언트(Lettuce)가 slot 맵을 다시 읽는 데 2.6 s. 반대로 옛 Primary 의 재합류는 0.5 s 로 Sentinel(10 s)보다 20 배 빠르다. → [EXP-AB](experiments/EXP-AB-primary-termination.md), [EXP-G](experiments/EXP-G-primary-rejoin.md)

### Q4. Sentinel 은 프록시인가요?

아니다. 앱은 Sentinel 에 `SENTINEL get-master-addr-by-name` 으로 주소를 묻고 Redis 에 직접 붙는다. 그래서 Sentinel 이 전부 죽어도 Redis 가 정상이면 서비스는 계속된다(실측 오류 0 %). 반대로 Failover 가 일어나도 앱이 다시 물어볼 계기가 없으면 옛 Primary 를 붙든다 — 다음 질문. → [EXP-DE](experiments/EXP-DE-replica-and-quorum.md)

### Q5. Spring Boot + Lettuce 는 승격을 언제 알았나요?

죽였을 때와 멈췄을 때가 다르다. SIGKILL 은 연결이 RST 로 끊기니 재연결 시 Sentinel 에 다시 묻고 1.2 s 안에 옮겨 간다. 그러나 `docker pause`(hang) 는 연결이 살아 있어 **40 초 동안 옛 Primary 에 보내고 전부 타임아웃**했다(오류율 98.2 %). Spring Boot 기본 Sentinel 연결은 `+switch-master` 를 구독하지 않는다. Lettuce 에서 구독하는 것은 `MasterReplica` 연결이고, `readFrom=UPSTREAM` 을 주면 Spring 이 그 연결을 쓴다 → 40.2 s 가 11.9 s 로, 오류율 98.2 % 가 29.6 % 로. 대가는 단순 종료가 6.6 → 11.1 s 로 느려지는 것. → [EXP-OPT](experiments/EXP-OPT-lettuce-options.md)

### Q6. Cluster 에서 클라이언트 토폴로지 갱신 설정은 왜 중요한가요?

Lettuce Cluster 는 갱신 옵션이 꺼져 있으면(라이브러리 기본값) 죽은 노드 주소로 재연결만 반복한다. 갱신 없음은 5회 중 3회가 복구(40 s)까지 실패했고, adaptive 트리거만 켜도 5회 모두 실패했다. 이유: 재연결 실패 트리거가 kill 뒤 약 5 s 에 갱신을 한 번 돌리는데 FAIL 확정(7 s) 전이라 토폴로지가 그대로이고, 다음 갱신은 `adaptiveRefreshTriggersTimeout`(기본 30 s) 쿨다운에 막힌다. adaptive + periodic 5 s 를 함께 켜야 9.7 s 에 복구됐다(오류율 35.9 % → 10.1 %). → [EXP-OPT](experiments/EXP-OPT-lettuce-options.md)

### Q7. 성공 응답을 받은 쓰기가 사라지는 걸 어떻게 측정했나요?

앱이 ACK 를 받은 쓰기를 전부 메모리 장부에 남기고(키·순번·시각), 복구 후 Redis 의 실제 값과 대조했다. 유실 = 사라진 메시지 + INCR 손실 + "마지막 ACK 보다 낮은 값으로 남은" 키. 복제 지연 500 ms 상태에서 Sentinel Primary 를 죽이면 25건(0.37 %) 이 사라졌다. 복제가 비동기라 Primary 가 ACK 한 뒤 Replica 에 못 간 쓰기다. → [EXP-F](experiments/EXP-F-replication-lag-loss.md), [metrics.md](metrics.md)

### Q8. Cluster 는 단순 SIGKILL 에서도 35건이 유실됐는데 복제 지연이 없었잖아요?

그래서 원인이 복제가 아니었다. Lettuce 의 `disconnectedBehavior=DEFAULT` 는 끊긴 동안 명령을 버퍼에 쌓고 재연결 뒤 보낸다. 앱에서는 1 s 타임아웃으로 실패 처리된 명령이 복구 때 옛 노드 재연결 → MOVED → 새 Primary 에서 실행되며 더 새로운 값을 옛 값으로 덮어썼다(유실 35) 거나 INCR 을 한 번 더 했다(중복 30). `REJECT_COMMANDS` 로 바꾸자 둘 다 정확히 0. Sentinel 은 SIGKILL 이 RST 로 끊어 버퍼가 버려지니 0 이었다. → [EXP-OPT](experiments/EXP-OPT-lettuce-options.md)

### Q9. 재시도를 넣으면 좋아지나요?

읽기 오류율만 조금 줄고 INCR 중복이 27 → 66 으로 두 배가 됐다(Cluster). 짧은 타임아웃(200 ms)은 중단 시간을 줄이지 못하고 유실을 두 배(59)로 늘렸다 — 같은 시간에 실패 처리된 채 버퍼에 남는 명령이 많아지기 때문. 재시도는 멱등한 연산에만, 그리고 클라이언트 버퍼 동작을 먼저 정리한 뒤에. → [EXP-OPT](experiments/EXP-OPT-lettuce-options.md)

### Q10. 메시지 중복은 어떻게 막았나요?

방 단위 Lua 스크립트 하나에서 `SETNX dedupe:{msgId}` → `INCR seq` → `XADD stream` 을 원자적으로 한다. 클라이언트가 같은 msgId 로 재시도해도 두 번째는 dedupe 에 걸린다. 실측에서 스트림 중복은 모든 시나리오에서 0 이었다. Cluster 에서는 세 키가 같은 slot 에 있어야 하므로 `room:{42}:*` Hash Tag 를 쓴다. → [EXP-CROSSSLOT](experiments/EXP-CROSSSLOT-hash-tag.md)

### Q11. CROSSSLOT 을 실제로 봤나요?

`room:42:seq`(slot 10,714), `room:42:stream`(8,935), `room:42:dedupe:m1`(1,987) 은 두 노드에 흩어져 같은 Lua 가 `CROSSSLOT Keys in request don't hash to the same slot` 을 냈다. `room:{42}:*` 로 바꾸면 세 키가 slot 8,000 에 모여 성공. MGET 은 slot 이 달라도 Lettuce 가 노드별로 나눠 보내 성공한다(Lua 는 불가). → [EXP-CROSSSLOT](experiments/EXP-CROSSSLOT-hash-tag.md)

### Q12. 네트워크 단절과 프로세스 종료는 무엇이 다르나요?

종료는 "모두가 동시에 못 보는" 장애라 timeout 뒤 정해진 순서로 복구된다. 단절은 누가 못 보느냐에 따라 셋으로 갈렸다. Primary↔Sentinel 만 끊기면 Failover 는 되는데 앱은 옛 Primary 에 계속 써서 **오류 0 % 에 승인 쓰기 75 %(4,084건) 조용히 유실**. 앱↔Primary 만 끊기면 Sentinel 은 정상이라 Failover 가 없고 앱만 52 초 멈춘다. Cluster 에서 Primary↔다른 노드가 끊기면 과반을 못 보는 Primary 가 node-timeout 뒤 스스로 쓰기를 거부해 유실이 151건(2.8 %) 으로 제한된다. → [EXP-C](experiments/EXP-C-network-partition.md)

### Q13. split-brain 은 어떻게 막나요?

Sentinel 은 `min-replicas-to-write 1` + `min-replicas-max-lag 1`. Replica 를 잃은 옛 Primary 가 `NOREPLICAS` 로 쓰기를 거부해 조용한 유실 4,084 → 시끄러운 실패 73건(+ 쓰기 오류 37 %)이 됐다. hang 장애의 유실 1,310 도 0 이 됐다. 대가는 가용성: Replica 링크가 잠깐 흔들려도 쓰기가 거부된다. Cluster 는 과반 규칙이 내장돼 있어 별도 설정 없이 창이 node-timeout 으로 닫힌다. → [EXP-SETTINGS](experiments/EXP-SETTINGS-data-safety.md)

### Q14. `min-replicas-max-lag` 가 복제 지연 유실을 막지 못한 이유는?

lag 판정이 REPLCONF ACK 기준 **초 단위**다. 500 ms 지연에서는 lag 가 1 s 를 넘지 않아 쓰기가 계속 승인되고 유실은 32건으로 그대로였다. Cluster 에 이 설정을 주면 승격된 Replica 에 Replica 가 0대라 자기 slot 의 쓰기를 40 초 거부했다 — Primary 당 Replica 2대 이상에서만 쓸 수 있는 설정. → [EXP-SETTINGS](experiments/EXP-SETTINGS-data-safety.md)

### Q15. AOF 를 켜면 유실이 줄어드나요?

승격 유실과는 무관하다. `appendfsync always` 에서도 유실은 29건으로 같았고 쓰기 p95 만 0.68 → 1.62 ms(2.4배). AOF 가 막는 것은 다른 장애다: Replica 가 없는 상태에서 Primary 가 죽었다 빈 상태로 재기동하면 데이터 전량(Sentinel 2,790건, 47 %)이 사라지는데, 그때 자기 데이터를 갖고 오는 수단이 AOF 다. → [EXP-SETTINGS](experiments/EXP-SETTINGS-data-safety.md), [EXP-DE](experiments/EXP-DE-replica-and-quorum.md)

### Q15-2. `WAIT` 를 쓰면 승인 쓰기 유실이 사라지나요?

Sentinel 에서는 사라졌다: 복제 지연 500 ms 상태의 Primary 종료에서 유실 29건 → 0건(5회 모두). 쓰기 뒤 `WAIT 1 1000` 을 호출해 Replica 1대가 ACK 한 쓰기만 승인했기 때문이다. 대가는 둘이다. 복제가 정상일 때는 같은 호스트라 p95 가 0.68 ms 로 기본과 같았지만, 지연 500 ms 상태에서는 쓰기 p95 가 약 950 ms 로 뛰었다. 그리고 그 대기가 1 s 를 넘으면 TIMEOUT 으로 실패 처리되는데, 그중 INCR 51·30건(5회 중 2회)은 Primary 에 이미 반영된 뒤였다 — **WAIT 실패는 "쓰기 안 됨" 이 아니라 "복제 확인 안 됨"** 이라 재시도하면 진짜 중복이 된다. WAIT 는 호출한 연결을 막는 명령이라 Lettuce 의 공유 연결 하나에서는 다른 명령도 함께 기다린다. Cluster 에서는 유실이 40 → 7 로 줄었지만 승격된 Replica 에 Replica 가 없어 WAIT 가 채워지지 않아 40 초 동안 쓰기가 전부 실패했다(min-replicas 와 같은 절벽) — Replica 2대 이상에서만 쓸 수 있다. → [EXP-SETTINGS](experiments/EXP-SETTINGS-data-safety.md)

### Q16. quorum 과 과반은 같은 건가요?

다르다. Sentinel 의 quorum(2) 은 "ODOWN 판정에 동의해야 하는 수" 이고, 실제 Failover 는 별개로 Sentinel **과반**(3대 중 2대) 리더 선출이 필요하다. Sentinel 2대를 멈추고 Primary 를 죽이면 남은 1대는 SDOWN 만 내고 승격 못 해 44 초(복구까지) 전면 중단. Cluster 도 Primary 과반이 있어야 FAIL 을 확정하고 투표하므로 Primary 2/3 를 죽이면 47 초 멈췄다. → [EXP-DE](experiments/EXP-DE-replica-and-quorum.md)

### Q17. `cluster-require-full-coverage` 는 언제 바꾸나요?

Primary 와 그 Replica 를 함께 잃어 slot 이 비면 기본값 `yes` 는 **모든 노드가 모든 키에 CLUSTERDOWN** 을 돌려준다(오류율 88.6 %, 성공 0건 33 초). `no` 면 그 slot 만 실패(35.5 %, 성공 0건 0 초). 채팅에서 "일부 방이 안 열리는 것" 과 "전체가 멈추는 것" 중 무엇이 나은가의 업무 결정이다. 어느 쪽이든 그 shard 의 데이터는 빈 노드로 돌아오므로 영속화 없이는 Replica 1대 구성이 견디는 장애는 정확히 한 번이다. → [EXP-H](experiments/EXP-H-slot-gap.md)

### Q18. `TCP_USER_TIMEOUT` 이나 keepalive 로 hang 을 잡을 수 있지 않나요?

못 잡았다. `docker pause` 는 프로세스를 멈추지만 그 컨테이너의 커널 TCP 스택은 계속 ACK 를 보낸다. "응답이 없는 것"(애플리케이션 계층)과 "ACK 가 없는 것"(전송 계층)은 다른 사건이라 TCP_USER_TIMEOUT 은 발동하지 않았고 Sentinel pause 는 40.2 s 그대로였다. hang 은 응답 타임아웃을 연결 종료로 이어 주는 로직(또는 `+switch-master` 구독)이 있어야 잡힌다. → [EXP-OPT](experiments/EXP-OPT-lettuce-options.md)

### Q19. 옛 Primary 가 돌아오면 무엇이 되나요? 분기된 쓰기는?

두 구성 모두 Replica 로 합류하고 분기된 쓰기는 full resync 로 폐기된다. 합류에 Cluster 0.5 s, Sentinel 10 s. Sentinel 의 그 10 초는 앱 연결이 옛 노드에 남아 있으면 유실 창이 된다. 함정 하나: 설정 파일이 읽기 전용이면 재시작한 노드가 이전 역할을 기억하지 못해 부트스트랩 역할로 돌아가고, 세 노드가 서로의 Replica 인 순환 상태까지 나왔다(Sentinel 매트릭스 전체 재측정). → [EXP-G](experiments/EXP-G-primary-rejoin.md), [ADR-006](adr/ADR-006-persist-node-config.md)

### Q20. 이 결과를 실무에 어떻게 옮기나요?

Sentinel 을 쓴다면 `readFrom=UPSTREAM`(MasterReplica) 과 `min-replicas-to-write` 를 기본으로 두고 down-after 를 네트워크 오탐 한계까지 내린다. Cluster 를 쓴다면 Lettuce 에 adaptive + periodic 갱신과 `REJECT_COMMANDS` 를 켜고, Hash Tag 로 키를 설계하며, `require-full-coverage` 를 업무에 맞게 정한다. 둘 다: 재시도는 멱등 연산에만, 설정 파일은 쓰기 가능하게, 그리고 **재봐야 안다** — 이 실험은 한 장비의 Docker 안이라 절대값이 아니라 상대 비교다. → [ADR-001](adr/ADR-001-sentinel-vs-cluster.md)

### Q21. 장애 주입 API 는 위험하지 않나요?

허용된 시나리오는 enum 이 전부이고 컨테이너 이름은 고정 IP 표에서만 나온다. `local-experiment` 프로필에서만 빈이 뜨고, 관리자 토큰·`confirm=true`·동시 1개·최대 180 s 자동 복구·실패 시 전체 원복·이력 저장을 둔다. Docker 소켓은 앱에 마운트하지 않고 허용 경로만 여는 프록시를 거치며, 네트워크 장애는 사이드카 안의 고정 스크립트만 실행한다. → [ADR-003](adr/ADR-003-fault-injection.md)

### Q22. 측정에서 가장 크게 틀렸던 것은?

세 가지. (1) Cluster 승격 시간이 −2 ms — 200 ms 폴링 한 틱에 두 사건이 같이 잡혀 Redis 로그로 보정했다. (2) 파티션의 "서비스 중단 15 ms" — 옛 Primary 가 계속 쓰기를 받아 중단이 없어 보였고, "첫 실패 → 첫 성공" 으로 재정의했다. (3) 호스트 절전 — Docker VM 시계가 뛰어 Sentinel 이 TILT 모드에 들어갔고, 감지 35 초짜리 이상치가 나왔다. 원인을 밝혀 격리하고 재측정했다. → [devlog](devlog.md)

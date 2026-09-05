# EXP-A/B. Primary 종료 — 정상 종료(SIGTERM), 강제 종료(SIGKILL), 프로세스 정지(pause)

```text
실험 이름: faults/STOP_PRIMARY, faults/KILL_PRIMARY, faults/PAUSE_PRIMARY (Sentinel, Cluster 각 5회)
검증하려는 가설: (1) 감지 시간은 종료 방식과 무관하게 down-after / node-timeout(5 s)이 지배한다. (2) SIGTERM 은 연결이 즉시 닫혀 클라이언트가 빨리 알지만 서버 측 감지는 같다.
                (3) pause 는 연결이 살아 있어 가장 나쁘다. (4) Cluster 는 해당 slot(1/3)만 실패한다.
변경한 항목: 장애 방식만 (docker stop -t 2 / docker kill / docker pause). 대상은 실행 시점의 Primary(Cluster 는 slot 0–5460 담당).
통제한 조건: 같은 앱 이미지·Lettuce 설정(command timeout 1 s, autoReconnect, Cluster adaptive+periodic 5 s refresh), 같은 워크로드(300 RPS, GET50/SET20/INCR10/SEND15/MGET5,
            사용자 10,000, 방 100), 워밍업 10 s → 정상 30 s → 장애 40 s → 복구 40 s, 실험마다 FLUSHALL, 시작 전 토폴로지 안정·쓰기 프로브 확인.
서버 및 DB 사양: Apple M5 10코어 32 GB 위 Docker Desktop VM. Redis 7.4 노드 cpus 1.0 / 512 MB, 앱 cpus 2.0 / 1 GB(JVM -Xmx512m), MySQL(기록 전용) 별도.
                Sentinel: Primary 1 + Replica 2 + Sentinel 3(quorum 2, down-after 5 s, failover-timeout 20 s). Cluster: Primary 3 + Replica 3, node-timeout 5 s, require-full-coverage yes.
테스트 데이터: 값 64 B 수준의 presence/unread/메시지 스트림. 실험 시작 시 비어 있음.
동시 사용자: 초당 300 요청(개방형, 앱 내부 워크로드, 가상 스레드).
전체 요청 수: 실행당 약 36,000 (120 s × 300), 장애 구간 12,000.
테스트 지속시간: 실행당 2 분, 시나리오당 5회, 총 30회.
```

## 측정 결과 (5회 중앙값 [최소–최대], `docs/results.md` 1절·1.1절·부록 A)

| 시나리오 | 구성 | 감지 T1−T0 | 승격 T3−T2 | 앱 인식 T4−T3 | 서비스 중단 T5−T0 | 장애 중 오류율 | 성공 0건 초 | 승인 쓰기 유실 | INCR 중복 | 재합류 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGKILL | Sentinel | 5,008 [4,983–5,055] | 335 [295–467] | 1,178 [148–1,336] | **6,566** [5,467–6,716] | 13.6% | 6 s | 0 | 0 | 10,505 |
| SIGKILL | Cluster | 7,114 [6,908–8,205] | 811 [712–1,019] | 2,562 | **12,032** [9,231–12,809] | 11.8% | 0 s | 35 [28–42] | 30 [18–35] | 501 |
| SIGTERM | Sentinel | 4,984 [4,425–9,335] | 603 | 1,118 | **6,553** [5,447–11,746] | 15.7% | 6 s | 0 [0–1] | 0 | 10,311 |
| SIGTERM | Cluster | 7,262 [6,275–7,753] | 812 | 1,033 | **10,404** [8,618–12,392] | 10.9% | 0 s | 38 | 34 | 355 |
| pause | Sentinel | 5,352 | 813 | 44,334 (복구 후) | **40,180** [40,132–41,381] | 98.2% | 39 s | 1,310 (24.0%) | 0 | 10,146 |
| pause | Cluster | 7,161 | 813 | 1,562 | **10,514** [8,911–12,175] | 11.0% | 0 s | 16 | 13 | 259 |

5회 값(서비스 중단 ms): Sentinel SIGKILL 6,566 / 5,468 / 5,467 / 6,716 / 6,690 (평균 6,181, 표준편차 654, 변동계수 10.6%). 1차 측정의 3회차는 세 Sentinel 이 동시에 TILT 모드(`+tilt`)에 들어가 48,688 ms 가 나왔는데, 호스트 절전으로 VM 시계가 뛴 것이 원인이라(devlog #13) 격리하고 다시 잰 값이다. 5.5 s 와 6.7 s 로 갈리는 것은 Sentinel 리더가 승격 뒤 앱 연결을 끊는 순간과 앱의 재조회 타이밍 차이다. Cluster SIGKILL 12,291 / 9,231 / 12,809 / 10,210 / 12,032 (변동계수 13%): 편차는 선거 지연(`Start of election delayed for 500~1,000 ms`)과 Lettuce 의 갱신 시점(주기 5 s 와 adaptive 트리거의 조합)에서 나온다.

## 병목 원인

- **감지**: Sentinel 은 `down-after-milliseconds`(5,000) 직후 `+sdown`→`+odown`(quorum 2) 이 50 ms 안에 이어져 5.0 s. Cluster 는 `cluster-node-timeout`(5,000) 뒤 PFAIL, 그 뒤 gossip 으로 Primary 과반의 PFAIL 보고가 모여야 FAIL 이 되므로 로그 기준 7.1 s(PFAIL 은 6.9 s 에 관측). 두 값 모두 종료 방식(SIGTERM/SIGKILL/pause)과 무관했다 — 가설 (1) 확인. Redis 는 종료 시 "이제 죽는다" 를 감시자에게 알리지 않으므로 SIGTERM 이라도 timeout 을 그대로 기다린다.
- **승격**: Sentinel 은 리더 선출 + `REPLICAOF NO ONE` + INFO 확인으로 0.4 s. Cluster 는 Replica 가 `500 ms + random(0~500) + rank×1 s` 만큼 기다린 뒤 선거(선거 자체는 3 ms)를 하므로 0.8 s.
- **클라이언트 인식**: Sentinel 은 연결이 끊긴 뒤 재연결 때마다 Sentinel 에 master 를 다시 물어 새 Primary 로 붙는다(1.2 s, 재연결 backoff 포함). Cluster 는 Lettuce 가 토폴로지를 다시 읽어야 하는데 adaptive 트리거(PERSISTENT_RECONNECTS·MOVED)와 5 s 주기 갱신이 맞물려 2.6 s 가 걸렸다. 이 항목이 Cluster 중단 시간의 차이(12.0 vs 6.6 s) 중 절반을 만든다 → EXP-OPT 에서 갱신 옵션별로 비교.
- **pause 가 Sentinel 에서 40 s 인 이유**: 정지된 프로세스는 TCP 연결을 닫지 않는다. Sentinel 은 6.2 s 에 승격을 끝냈지만(`+switch-master`), Spring 의 기본 Lettuce Sentinel 연결(`RedisClient.connect(sentinelURI)`)은 `+switch-master` 를 구독하지 않고 **재연결 때만** master 를 다시 조회한다(Lettuce 6.6 소스에서 `+switch-master` 를 다루는 곳은 `masterreplica/SentinelTopologyRefresh` 뿐). 명령 타임아웃은 연결을 끊지 않으므로 앱은 40 s 내내 멈춘 노드에 붙어 있었다. 복구(unpause) 뒤에는 옛 Primary 가 자기가 master 라고 믿는 10 초 동안 앱의 쓰기를 받았고(`FIRST_WRITE_OK` 가 복구 직후), Sentinel 이 `+convert-to-slave` 로 강등시키며 그 쓰기 1,310 건(24%)이 버려졌다. Cluster 는 같은 pause 에서 토폴로지 갱신으로 10.5 s 만에 새 Primary 로 옮겨 갔다.

## 해석

- 가설 (2)는 반만 맞았다. SIGTERM 은 첫 쓰기 실패가 5 ms 로 즉시 드러나지만(연결 종료), 서버 측 감지·승격이 같아 중단 시간은 SIGKILL 과 구별되지 않았다(Sentinel 6.55 vs 6.57 s, Cluster 10.4 vs 12.0 s 는 반복 편차 안).
- 가설 (4) 확인: Cluster 는 "성공 0건인 초" 가 0 이다. 다른 두 shard(slot 5461–16383)는 장애 내내 정상이었고 오류율 11~12% 는 영향 slot 의 비율(1/3) × 중단 시간(12 s)/창(40 s) 과 맞는다. Sentinel 은 6 초 동안 전부 실패한다.
- **Cluster 의 유실 35·중복 30 은 복제 유실이 아니라 "늦은 실행" 이다**(추정, EXP-OPT 의 `reject-commands` 변형으로 확인 예정). 잘못된 값 키 수(35)와 유실 수(35)가 같고 메시지(SEND, 멱등 Lua) 유실은 0 이다. Lettuce 기본 `disconnectedBehavior=DEFAULT` 는 끊긴 동안의 명령을 버퍼에 두고 재연결 뒤 보낸다. 죽은 Primary 로 향하던 SET/INCR 은 클라이언트에서 1 s 에 타임아웃 처리됐지만, 복구 때 그 노드(이제 Replica)와 다시 연결되자 버퍼가 비워지고 MOVED 를 따라 새 Primary 에서 실행됐다 — 이미 새 값이 있는 presence 를 옛 seq 로 덮어쓰고(잘못된 값), unread 는 한 번 더 증가(중복). Sentinel 은 SIGKILL 시 연결이 RST 로 끊기며 그 명령이 버려져 0 이었고, 파티션처럼 연결이 살아 있는 경우에는 Sentinel 도 중복이 났다(EXP-C: 앱↔Primary 단절 295건).
- 옛 Primary 재합류: Sentinel 10.3~10.4 s(재기동한 노드가 master 로 뜬 뒤 Sentinel 이 `+convert-to-slave` 하기까지의 고정 지연), Cluster 0.3~0.5 s(gossip 에서 더 높은 epoch 를 보고 즉시 Replica 로). 둘 다 full resync(재기동으로 replid 가 바뀜).

## 부작용

- Sentinel 은 복구된 옛 Primary 가 10 초 동안 master 로 남는다. 앱 연결이 거기에 붙어 있으면(pause 사례) 그 10 초의 쓰기가 사라진다. `min-replicas-to-write 1` 이면 replica 가 없는 옛 Primary 가 쓰기를 거부해 창을 닫을 수 있다(EXP-F/설정 비교에서 측정).
- Cluster 의 늦은 실행은 조용히 값을 되돌린다. 멱등 키를 검사하는 Lua(SEND)는 영향이 없었고, 단순 `SET`/`INCR` 만 영향받았다.

## 결론

같은 5 초 timeout 에서 Sentinel 은 6.6 s, Cluster 는 12.0 s 만에 쓰기가 재개된다. 차이는 gossip 기반 FAIL 확정(+2 s), 선거 지연(+0.4 s), 클라이언트 토폴로지 갱신(+1.4 s)에서 온다. 대신 Cluster 는 장애가 1/3 의 키에만 미치고 재합류가 0.5 s 로 빠르다. hang 형 장애에는 Sentinel 기본 클라이언트 설정이 무방비였다.

## 다음 실험

EXP-OPT(Lettuce 옵션): `tcp-user-timeout 3 s`, `readFrom=UPSTREAM`(MasterReplica 연결, `+switch-master` 구독), `disconnectedBehavior=REJECT_COMMANDS`, 재전송 필터, 갱신 주기별 비교. EXP-TIMING: down-after / node-timeout 1 s·15 s.

# 개발 기록 — 실패한 접근과 고친 과정

측정 파이프라인을 만들며 막혔던 지점과 해결을 시간순으로 적는다. 실험 결과의 해석은 `docs/experiments/`, 수치는 `docs/results.md` 에 있다.

| # | 막힌 지점 | 원인 | 해결 |
|---|---|---|---|
| 1 | Docker `exec` 를 API 로 호출하면 `Frame type(0) length(65536) exceeds MAX_FRAME_SIZE` | `Detach=false` 는 연결을 raw 스트림으로 hijack 하고 Java HttpClient 는 이를 HTTP/2 프레임으로 오해 | `Detach=true` 로 실행 후 `/exec/{id}/json` 폴링으로 종료 코드만 확인 |
| 2 | 그 다음엔 `HTTP/1.1 header parser received no bytes` | docker-socket-proxy(HAProxy)가 닫은 keep-alive 연결을 HttpClient 가 재사용 | Docker API 호출마다 새 HttpClient(연결) 사용 |
| 3 | 앱 컨테이너에 `LETTUCE_*`, `LAB_*` 환경변수가 안 들어감 | compose `environment` 에 명시하지 않은 변수는 전달되지 않음 | 실험용 변수를 기본값과 함께 전부 명시 |
| 4 | 배치가 `finish` 직후 죽음 | `set -e` 상태에서 `wait <killed pid>` 가 143 반환 | `|| true` |
| 5 | Cluster 의 승격 시간이 −2 ms 로 나옴 | 워처 200 ms 폴링 한 틱 안에 epoch 증가와 role 전환이 같이 관측됨 | Redis 로그(`Start of election delayed`, `Failover election won`)를 Docker API 로 읽어 T2·T3 보정. 같은 VM 시계라 앱 시각과 뺄셈 가능 |
| 6 | 파티션 시나리오의 "서비스 중단" 이 15 ms | T5 를 "장애 주입 후 첫 성공 쓰기" 로 두면, 옛 Primary 가 6 초 동안 계속 쓰기를 받는 파티션에서는 중단이 없는 것처럼 보임 | T5 = "영향 shard 로 향한 쓰기가 처음 실패한 뒤 첫 성공"(`request_log` 기준) 으로 재정의, 첫 실패 시각과 쓰기 공백을 함께 기록 |
| 7 | Sentinel 실험 3개 × 3회의 기준 구간에 45% `READONLY` 오류 | 앞 실험에서 승격됐던 노드가 재시작하며 부트스트랩 역할(replica)로 돌아왔고, 앱의 Lettuce 연결은 재연결 계기가 없어 replica 에 붙은 채 다음 실험까지 이어짐 | 9회 격리·재측정, 실험 시작 전 쓰기 프로브 + 연결 리셋(`POST /api/redis/reset-connection`) 가드 |
| 8 | 세 노드가 서로의 replica 인 순환 상태(master 없음) | 읽기 전용 설정 + 명령줄 `--replicaof` 라 `CONFIG REWRITE` 가 안 됨. Sentinel 도 재시작 시 상태 초기화 | ADR-006: 설정을 `/data` 에 두고 첫 기동에만 생성. Sentinel 매트릭스 전체 재측정 |
| 9 | Grafana Failover 패널이 항상 0 s | Micrometer Timer 의 `_max` 는 2분 창이 지나면 사라짐 | 최근 값을 Gauge(`redis_failover_last_seconds`)로 별도 노출 |
| 10 | Testcontainers 로 Cluster 테스트가 macOS 에서 실행 불가 | Cluster 노드가 컨테이너 IP 를 announce 하는데 macOS 호스트는 그 IP 에 닿지 않음 | 닿는지 확인 후 `Assumptions.assumeTrue` 로 건너뛰고, CROSSSLOT 은 compose 스택 대상 통합 테스트로 검증 |
| 11 | Toxiproxy 로 앱↔Primary·Replica↔Primary 를 끊을 수 없음 | Sentinel/Cluster 가 노드의 실제 주소를 알려주므로 고정 프록시는 Failover 후 우회됨. Sentinel 은 "잘못된 master 를 보는 replica" 를 되돌리기까지 함 | 노드 netns 를 공유하는 사이드카에서 iptables/tc. Toxiproxy 는 주소가 정적인 앱↔Sentinel 경로에만 |
| 12 | 실험 간 자원 경합 | 앱·Redis·k6·MySQL 이 한 장비의 Docker VM 을 공유 | 절대값이 아니라 같은 조건의 상대 비교로만 해석, 5회 반복과 편차 기록 |
| 13 | Sentinel SIGKILL 1회의 감지가 35 s(`+tilt`), 설정 배치 중 한 실행이 36분간 정지, 컨테이너 시계가 호스트보다 17분 뒤짐 | 이 Mac 은 유휴 1분 뒤 시스템 절전(`pmset sleep 1`)이 걸려 있었고 아무 프로세스도 절전을 막지 않던 구간에 잠들었다. Docker VM 이 멈췄다 깨면서 시계가 뛰고 Sentinel 은 TILT 모드로 30 s 판단을 멈춘다 | `caffeinate -ims` 를 배치와 함께 실행. 영향받은 실행은 부록 D 에 원인과 함께 표시, 설정 원복용 실행은 분석에서 제외 |
| 14 | k6 성능 배치 14회(1.7 h)가 부하 없이 지나감 | 러너가 `k6 run --no-summary` 를 썼는데 k6 v2.1 에는 그 플래그가 없어 즉시 종료(`unknown flag`). 스모크에서는 앱 내부 워크로드(DRIVER=app)만 검증했고 k6 경로는 미검증 | 플래그 제거, 3초 짜리 k6 실행으로 단계별 지표가 JSON 에 남는지 확인 후 성능 단계 재실행. 교훈: 드라이버가 둘이면 둘 다 스모크해야 한다 |
| 15 | `WAIT 1` 변형의 기준(BEFORE) 구간에 4.7 % 오류 | 복합 시나리오(복제 지연 → kill)는 kill 을 T0 로 잡고 netem 은 그 10 s 전에 건다. 비동기 복제에서는 앱이 지연을 못 느껴 BEFORE 가 깨끗했지만, WAIT 를 켜면 그 10 s 동안 쓰기가 Replica ACK 를 기다려 p95 가 0.7 → 950 ms 로 뛰고 1 s 타임아웃을 넘는 회차가 생겼다 | 오류가 T0 직전 10 s 에만 몰린 것을 초별 표본으로 확인해 "선행 단계 구간" 으로 해석하고 문서에 그대로 적었다(EXP-SETTINGS). 교훈: 복합 장애의 T0 는 마지막 단계지만, 클라이언트 설정에 따라 선행 단계도 관측 가능한 장애가 된다 |
| 16 | 고부하 k6 실행에서 오류율이 낮아지는데 실제 RPS 는 목표보다 낮음 | 장애 구간에 요청이 5 s 까지 대기하자 최대 VU(2×RPS)가 다 차서 k6 가 새 반복을 버렸다(`dropped_iterations` 최대 4.6 %). 서버 쪽은 Tomcat 200 스레드가 죽은 Primary 를 1 s 씩 기다리는 요청으로 포화돼 TIMEOUT 건수가 1,200 으로 고정됨 | 오류율과 함께 드롭·p99·HTTP_ERR 를 같은 표에 두고 "오류 + 드롭" 으로 읽는 규칙을 EXP-PERF 에 명시. 부하 생성기 지표도 실험 결과의 일부다 |
| 17 | Redis 장애가 없는 Toxiproxy 실험 중 세 Sentinel 이 동시에 `+tilt` | 호스트 절전 기록·앱 초별 표본 공백·컨테이너-호스트 시계 차이가 모두 없었다. 세 프로세스가 같은 순간 시계 점프를 본 것이므로 VM 시계 보정(역행)으로 추정, 미검증 | 결과 영향 없음(Redis 장애 없는 실험, TILT 30 s 뒤 해제, 다음 배치와 겹치지 않음)을 확인하고 EXP-MISC 각주에 남김. 절전만이 TILT 의 원인은 아니다 |


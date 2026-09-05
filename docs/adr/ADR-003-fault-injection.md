# ADR-003 장애 주입은 "허용된 시나리오 → 고정 실행기" 구조로 한다

**상태**: 채택 (2026-09-05)

**문제**: 대시보드에서 노드 종료·네트워크 단절을 눌러야 하지만, 임의 컨테이너 이름·셸 명령을 받는 API 는 그 자체가 원격 코드 실행이다. 또 Sentinel/Cluster 는 클라이언트에게 노드의 실제 주소를 알려주므로 앱↔Primary 사이에 고정 프록시(Toxiproxy)를 끼워 넣어도 Failover 후 우회된다.

**선택**
- 요청은 `FaultScenario` enum + 논리 대상(`primary`, `replica-1`, `sentinel-2`, `primary-2`) + 정수 파라미터(범위 검사)만 받는다. 컨테이너 이름은 `FaultCatalog`(고정 IP 표)에서만 나온다.
- 프로세스 장애(stop/kill/pause/start)는 `docker-socket-proxy`(내부 네트워크 전용, containers/exec/post 만 허용)를 거친 Docker API 로 한다. 소켓을 앱에 직접 마운트하지 않는다.
- 네트워크 장애는 각 Redis 노드의 네트워크 네임스페이스를 공유하는 사이드카(`fault-agent`, NET_ADMIN)에서 고정 스크립트(`drop.sh`, `netem.sh`, `clear.sh`)만 실행한다. 상대별 차단(Sentinel/Replica/앱/다른 노드)과 지연·손실이 링크 단위로 가능하다.
- Toxiproxy 는 주소가 정적인 앱↔Sentinel 경로에만 쓴다.
- `local-experiment` 프로필에서만 빈이 뜨고, `X-Lab-Admin-Token`, `confirm=true`, 동시 1개, 최대 지속 180 s 자동 복구, 실패 시 `RESTORE_ALL`, 모든 호출을 `fault_action` 에 기록한다.

**단점·한계**: 사이드카는 노드 컨테이너가 재시작되면 옛 네임스페이스에 남으므로 복구 시 사이드카도 재시작한다. `docker pause` 는 컨테이너 단위라 "프로세스만 멈춘" 것과 완전히 같지는 않다(네트워크 스택은 살아 있음). 컨테이너 재시작 시 IP 가 바뀌지 않도록 고정 IP 를 쓴다.

**검증**: `docker exec` 를 API 로 호출할 때 `Detach=false` 는 연결을 raw 스트림으로 hijack 해 Java HttpClient 가 실패했고(`Frame type(0)…`), `Detach=true` + 종료 코드 폴링으로 바꿔 해결했다. HAProxy 가 닫은 keep-alive 연결 재사용으로 `received no bytes` 가 나 요청마다 새 연결을 쓴다.

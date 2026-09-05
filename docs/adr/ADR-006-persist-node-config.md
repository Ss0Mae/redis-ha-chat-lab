# ADR-006 Redis·Sentinel 설정 파일은 컨테이너 재시작을 넘어 유지한다

**상태**: 채택 (2026-09-05, Sentinel 장애 매트릭스 1차 측정 후)

**문제**: 처음 구성은 `redis.conf` 를 읽기 전용으로 마운트하고 Replica 여부를 명령줄 `--replicaof` 로 줬으며, Sentinel 도 기동 때마다 템플릿에서 `sentinel.conf` 를 새로 만들었다. Sentinel 은 역할을 바꿀 때 `REPLICAOF` 뒤에 `CONFIG REWRITE` 를 보내는데 파일에 쓸 수 없으니 아무 것도 남지 않았고, 노드가 재시작하면 **부트스트랩 역할**(r-a-1 = master, 나머지 = r-a-1 의 replica)로 되돌아갔다. Sentinel 도 재시작하면 승격 결과·epoch 를 잊고 처음 설정(master = r-a-1)으로 돌아갔다.

**실측된 결과** (`results/faults-immutable/`, 1차 Sentinel 매트릭스 65회):
- `KILL_ALL_REPLICAS_THEN_PRIMARY` 복구 뒤: 승격됐던 r-a-2 가 재시작하며 다시 r-a-1 의 replica 로 기동 → Sentinel 은 아직 r-a-2 를 master 로 앎 → 앱이 r-a-2 에 붙어 모든 쓰기가 `READONLY` → Sentinel 이 21 초 뒤 `+switch-master` 로 r-a-1 을 master 로 되돌림. 앱은 재연결 계기가 없어 **다음 실험 3개(9회)의 기준 구간까지 45% 오류**로 오염됐다(격리 후 재측정).
- `STOP_SENTINELS_THEN_KILL_PRIMARY` 재실행: Primary 가 r-a-3 이던 상태에서 kill·복구 → r-a-3 은 "r-a-1 의 replica" 로 기동, r-a-1 은 "r-a-3 의 replica" 인 채 → **세 노드 모두 replica, master 없음** 인 순환 상태. Sentinel 은 master 를 `s_down,o_down` 으로만 표시하고 스스로 풀지 못했다(수동 `REPLICAOF NO ONE` 필요).
- 어느 노드가 Primary 였느냐(부트스트랩 master 인지)에 따라 재합류 시간이 300 ms ~ 21 s 로 갈렸다. 같은 시나리오의 반복값이 구성 차이가 아니라 "누가 죽었나" 에 좌우되는 교란 변수다.

**선택**: 노드 entrypoint 가 첫 기동에만 템플릿을 `/data/redis.conf` 로 복사하고(`replicaof` 는 파일에 기록), 이후 재시작은 `CONFIG REWRITE` 된 파일을 그대로 쓴다. Sentinel 도 `/data/sentinel.conf` 가 있으면 재생성하지 않는다. 확인: Failover 뒤 `docker exec r-a-1 grep replicaof /data/redis.conf` 에 새 master 주소, `s-1` 의 `sentinel.conf` 에 `sentinel monitor chat-primary <새 master>` 와 `known-replica` 가 기록됨.

**의미**: Kubernetes 의 ConfigMap 처럼 설정을 불변으로 두는 배포에서 그대로 재현되는 함정이다. Sentinel/Cluster 가 바꾼 역할을 노드가 스스로 기억하지 못하면 재시작이 곧 두 번째 장애가 된다. Cluster 는 `nodes.conf` 를 별도로 쓰기 때문에 이 문제가 없었고(1차 매트릭스 55회 유효), Sentinel 매트릭스만 설정 영속화 후 다시 측정했다(`results/faults/`).

**단점**: 컨테이너를 지우고 다시 만들면(`bench/switch.sh`) 설정이 초기화되므로 부트스트랩 역할로 시작한다 — 실험 사이에 환경을 초기화한다는 점에서는 오히려 원하는 동작이다.

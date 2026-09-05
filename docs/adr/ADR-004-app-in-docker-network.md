# ADR-004 앱은 Docker 네트워크 안에서 실행하고 노드는 고정 IP 를 쓴다

**상태**: 채택 (2026-09-05)

**문제**: macOS 호스트에서 Spring Boot 를 띄우면 Sentinel 이 알려주는 Primary 주소(컨테이너 IP)와 Cluster 의 MOVED 대상 주소에 닿지 못한다.

**대안**: `cluster-announce-ip 127.0.0.1` + 호스트 포트 매핑(Cluster 만 가능, Sentinel 은 방법 없음), 호스트 네트워크 모드(Docker Desktop 미지원).

**선택**: 앱을 컨테이너(`lab-app`, 172.28.0.100)로 실행. k6·React 는 공개 포트(8085)만 본다. 노드는 `ipv4_address` 로 고정해 재시작 후에도 Sentinel/Cluster/iptables 규칙이 같은 주소를 본다.

**부수 효과**: 앱·Redis·Sentinel 이 같은 VM 시계를 쓰므로 T0(앱)·T1~T3(Redis 로그·Sentinel 이벤트)·T5(앱)를 한 시계로 뺄 수 있다. 코드 수정마다 이미지를 다시 만들어야 하는 비용은 `bootJar` + `compose up --build app` 약 20 초.

**한계**: 앱·Redis·k6·MySQL 이 한 장비의 자원을 나눠 써 절대 처리량은 낮게 나온다. 비교는 같은 조건의 상대값으로만 한다.

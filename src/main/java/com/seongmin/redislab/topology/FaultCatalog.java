package com.seongmin.redislab.topology;

import com.seongmin.redislab.config.LabProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 구성별 노드 목록(고정 IP). 장애 주입은 이 표에 있는 이름만 허용한다. 사이드카 이름은 "fault-<node>".
 */
@Component
public class FaultCatalog {

	public record Node(String name, String ip, int port, String kind) {
		public String sidecar() { return "fault-" + name; }
		public boolean redis() { return "redis".equals(kind); }
	}

	public static final String APP_IP = "172.28.0.100";
	private final String topology;
	private final List<Node> nodes;

	public FaultCatalog(LabProperties lab) {
		this.topology = lab.topology();
		this.nodes = switch (lab.topology()) {
			case "cluster" -> List.of(new Node("c-1", "172.28.2.11", 6379, "redis"), new Node("c-2", "172.28.2.12", 6379, "redis"), new Node("c-3", "172.28.2.13", 6379, "redis"),
					new Node("c-4", "172.28.2.14", 6379, "redis"), new Node("c-5", "172.28.2.15", 6379, "redis"), new Node("c-6", "172.28.2.16", 6379, "redis"));
			case "sentinel", "sentinel-toxi" -> List.of(new Node("r-a-1", "172.28.1.11", 6379, "redis"), new Node("r-a-2", "172.28.1.12", 6379, "redis"), new Node("r-a-3", "172.28.1.13", 6379, "redis"),
					new Node("s-1", "172.28.1.21", 26379, "sentinel"), new Node("s-2", "172.28.1.22", 26379, "sentinel"), new Node("s-3", "172.28.1.23", 26379, "sentinel"));
			default -> List.of(new Node("r-0", "172.28.0.11", 6379, "redis"));
		};
	}

	public String topology() { return topology; }
	public List<Node> nodes() { return nodes; }
	public List<Node> redisNodes() { return nodes.stream().filter(Node::redis).toList(); }
	public List<Node> sentinels() { return nodes.stream().filter(n -> "sentinel".equals(n.kind())).toList(); }
	public Optional<Node> byName(String name) { return nodes.stream().filter(n -> n.name().equals(name)).findFirst(); }
	public Node require(String name) { return byName(name).orElseThrow(() -> new IllegalArgumentException("unknown node: " + name)); }
	public Optional<Node> byIp(String ip) { return nodes.stream().filter(n -> n.ip().equals(ip)).findFirst(); }
	public String nameOf(String ip) { return byIp(ip).map(Node::name).orElse(ip); }
}

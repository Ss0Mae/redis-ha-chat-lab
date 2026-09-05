package com.seongmin.redislab.topology;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TopologySnapshot(Instant at, String topology, List<NodeState> nodes, List<String> primaries, boolean stable, Map<String, Object> info) {
	public NodeState node(String name) { return nodes.stream().filter(n -> n.name().equals(name)).findFirst().orElse(null); }
}

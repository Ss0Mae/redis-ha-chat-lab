package com.seongmin.redislab.experiment;

import com.seongmin.redislab.fault.FaultRequest;
import com.seongmin.redislab.record.RecordingService;
import com.seongmin.redislab.workload.WorkloadConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 실험 1건의 상태. T0~T6 와 타임라인 이벤트, 최종 요약을 가진다. */
public class Experiment {

	public record TimelineEvent(Instant at, String source, String type, String detail) {}

	public String id, name, topology, hypothesis, status = "CREATED", failedNode;
	public int failedShard;
	public RecordingService.Mode recordingMode = RecordingService.Mode.MEMORY;
	public WorkloadConfig workload;
	public Map<String, String> redisSettings = new LinkedHashMap<>();
	public FaultRequest fault;
	public Instant createdAt = Instant.now(), t0, t1, t2, t3, tSwitchMaster, t4, t5, t6, recoveredAt, recoveredStableAt, finishedAt;
	public final List<TimelineEvent> events = new ArrayList<>();
	public Map<String, Object> summary = new LinkedHashMap<>();

	public Map<String, Object> timings() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("t0_inject", t0); m.put("t1_detected", t1); m.put("t2_promotion_start", t2); m.put("t3_promoted", t3);
		m.put("t3b_switch_master", tSwitchMaster); m.put("t4_client_aware", t4); m.put("t5_first_write_ok", t5); m.put("t6_stable", t6);
		m.put("recoveredAt", recoveredAt); m.put("recoveredStableAt", recoveredStableAt);
		m.put("detect_ms", ms(t0, t1)); m.put("promote_ms", ms(t2, t3)); m.put("client_reconnect_ms", ms(t3, t5));
		m.put("client_aware_ms", ms(t3, t4)); m.put("outage_ms", ms(t0, t5)); m.put("stabilize_ms", ms(t0, t6)); m.put("rejoin_ms", ms(recoveredAt, recoveredStableAt));
		return m;
	}

	static Long ms(Instant a, Instant b) { return a == null || b == null ? null : b.toEpochMilli() - a.toEpochMilli(); }
}

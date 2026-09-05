CREATE TABLE IF NOT EXISTS experiment (
  id VARCHAR(40) PRIMARY KEY, name VARCHAR(200) NOT NULL, topology VARCHAR(20) NOT NULL, hypothesis TEXT,
  status VARCHAR(20) NOT NULL, recording_mode VARCHAR(20) NOT NULL, workload JSON, redis_settings JSON,
  created_at DATETIME(3) NOT NULL, t0 DATETIME(3), t1 DATETIME(3), t2 DATETIME(3), t3 DATETIME(3), t4 DATETIME(3), t5 DATETIME(3), t6 DATETIME(3),
  recovered_at DATETIME(3), finished_at DATETIME(3), summary JSON
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS experiment_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, experiment_id VARCHAR(40) NOT NULL, at DATETIME(3) NOT NULL,
  source VARCHAR(30) NOT NULL, type VARCHAR(60) NOT NULL, detail VARCHAR(500), INDEX idx_exp (experiment_id, at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS request_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, experiment_id VARCHAR(40) NOT NULL, request_id VARCHAR(40) NOT NULL, seq BIGINT NOT NULL,
  op VARCHAR(10) NOT NULL, k VARCHAR(120) NOT NULL, v VARCHAR(120), requested_at DATETIME(3) NOT NULL, acked_at DATETIME(3),
  status VARCHAR(20) NOT NULL, latency_us INT NOT NULL, result_text VARCHAR(120), shard INT, INDEX idx_exp_seq (experiment_id, seq)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS metric_sample (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, experiment_id VARCHAR(40), at DATETIME(3) NOT NULL, phase VARCHAR(20) NOT NULL,
  ok INT NOT NULL, fail INT NOT NULL, by_op JSON, by_error JSON, p50_us INT, p95_us INT, p99_us INT, max_us INT, INDEX idx_exp_at (experiment_id, at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fault_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, experiment_id VARCHAR(40), at DATETIME(3) NOT NULL, scenario VARCHAR(60) NOT NULL,
  target VARCHAR(60), params JSON, result VARCHAR(300)
) ENGINE=InnoDB;

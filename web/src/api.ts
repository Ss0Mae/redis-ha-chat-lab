export type NodeState = {
  name: string; ip: string; port: number; role: string; status: string; masterOf: string | null; slots: string | null
  replOffset: number; lagBytes: number; linkStatus: string | null; lastChange: string | null; extra: Record<string, unknown>
}
export type Topology = { at: string; topology: string; nodes: NodeState[]; primaries: string[]; stable: boolean; info: Record<string, unknown> }
export type Sample = {
  at: string; phase: string; ok: number; fail: number; byOp: Record<string, number>; byError: Record<string, number>
  p50Us: number; p95Us: number; p99Us: number; maxUs: number; okWrites: number; failWrites: number; byShard?: Record<string, number>
}
export type LabEvent = { kind: string; at: string; source: string; type: string; payload: unknown }
export type Timings = Record<string, string | number | null>
export type WorkloadConfig = { rps: number; mix: { get: number; set: number; incr: number; send: number; mget: number }; users: number; rooms: number; hashTag: boolean; mgetSize: number }
export type FaultRequest = { scenario: string; target?: string | null; delayMs?: number | null; lossPct?: number | null; confirm: boolean }
export type PhaseStat = { seconds: number; ok: number; fail: number; error_rate_pct: number; tps: number; success_tps: number; p95_ms_median: number; p99_ms_median: number; p95_ms_max: number }
export type Summary = { timings?: Timings; consistency?: Record<string, unknown>; phases?: Record<string, PhaseStat>; recordingDropped?: number; consistency_error?: string; log_timings?: Record<string, { at: string; node?: string }> }
export type TimelineEvent = { at: string; source: string; type: string; detail: string }
export type Experiment = {
  id: string; name: string; topology: string; hypothesis: string | null; status: string; recordingMode: string; workload: WorkloadConfig | null
  redisSettings: Record<string, string>; fault: FaultRequest | null; failedNode: string | null; failedShard: number; createdAt: string; finishedAt: string | null
  timings: Timings; events: TimelineEvent[]; summary: Summary
}
export type ExperimentRow = { id: string; name: string; topology: string; status: string; recording_mode?: string; recordingMode?: string; created_at: string; t0: string | null; t5: string | null; finished_at: string | null; summary: string | Summary | null }
export type SampleRow = { at: string; phase: string; ok: number; fail: number; p50_us: number; p95_us: number; p99_us: number; max_us: number; by_error: string | null }
export type WorkloadStatus = { running: boolean; config: Partial<WorkloadConfig>; startedAt: string; submitted: number }

export type Scenario = { id: string; label: string; group: string; target: 'primary' | 'replica' | 'sentinel' | 'node' | 'none'; params: ('delayMs' | 'lossPct')[]; danger: boolean }
const S = (id: string, label: string, group: string, target: Scenario['target'], danger = true, params: Scenario['params'] = []): Scenario => ({ id, label, group, target, danger, params })
export const SCENARIOS: Scenario[] = [
  S('KILL_PRIMARY', 'Primary 강제 종료 (SIGKILL)', '프로세스', 'primary'),
  S('STOP_PRIMARY', 'Primary 정상 종료 (SIGTERM)', '프로세스', 'primary'),
  S('PAUSE_PRIMARY', 'Primary 일시정지 (연결은 살아 있음)', '프로세스', 'primary'),
  S('STOP_PRIMARY_AND_ITS_REPLICA', 'Primary 와 그 Replica 함께 종료', '프로세스', 'primary'),
  S('KILL_REPLICA', 'Replica 강제 종료', '프로세스', 'replica'),
  S('STOP_REPLICA', 'Replica 정상 종료', '프로세스', 'replica'),
  S('STOP_SENTINEL', 'Sentinel 1대 종료', '프로세스', 'sentinel'),
  S('STOP_ALL_SENTINELS', 'Sentinel 전부 종료', '프로세스', 'none'),
  S('PARTITION_PRIMARY_FROM_SENTINELS', 'Primary 와 Sentinel 사이 끊기', '네트워크', 'primary'),
  S('PARTITION_PRIMARY_FROM_REPLICAS', 'Primary 와 Replica 복제 끊기', '네트워크', 'primary'),
  S('PARTITION_PRIMARY_FROM_APP', '앱과 Primary 사이만 끊기', '네트워크', 'primary'),
  S('PARTITION_PRIMARY_FROM_PEERS', 'Primary 와 다른 Cluster 노드 끊기', '네트워크', 'primary'),
  S('NETEM_REPLICATION_DELAY', '복제 링크에 지연·손실 주기', '네트워크', 'primary', true, ['delayMs', 'lossPct']),
  S('NETEM_GLOBAL_DELAY', '모든 노드 송신 지연', '네트워크', 'none', true, ['delayMs']),
  S('NETEM_LOSS', '모든 노드 패킷 손실', '네트워크', 'none', true, ['lossPct']),
  S('TOXIC_APP_SENTINEL_TIMEOUT', '앱과 Sentinel 사이 응답 막기 (Toxiproxy)', 'Sentinel 경로', 'none'),
  S('TOXIC_APP_SENTINEL_LATENCY', '앱과 Sentinel 사이 지연 (Toxiproxy)', 'Sentinel 경로', 'none', true, ['delayMs']),
  S('RESTORE_NODE', '노드 하나 되살리기', '복구', 'node', false),
  S('CLEAR_NETWORK_FAULTS', '네트워크 장애 걷어내기', '복구', 'none', false),
  S('REMOVE_TOXICS', 'Toxic 제거', '복구', 'none', false),
  S('RESTORE_ALL', '전부 되살리기', '복구', 'none', false),
]
export const AUTO_RECOVER_SECONDS = 180

export function getToken(): string { try { return localStorage.getItem('lab-admin-token') ?? 'lab-admin' } catch { return 'lab-admin' } }
export function setToken(t: string) { try { localStorage.setItem('lab-admin-token', t) } catch { /* 저장 불가 환경 */ } }

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Lab-Admin-Token': getToken() },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let data: unknown = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  if (!res.ok) throw new Error((data as { error?: string } | null)?.error ?? `${res.status} ${res.statusText}`)
  return data as T
}

export const fmt = (n: number | string | null | undefined, d = 0) =>
  n == null || n === '' ? '–' : typeof n === 'string' ? n : n.toLocaleString('ko-KR', { minimumFractionDigits: d, maximumFractionDigits: d })
export const fmtMs = (ms: number | string | null | undefined) => ms == null || ms === '' ? '–' : `${fmt(Number(ms))} ms`
/** 승격 시간은 200 ms 폴링으로 재므로 그 안의 값(음수 포함)은 해상도 이하로 표시한다. */
export const fmtPromoteMs = (ms: number | string | null | undefined) => ms != null && ms !== '' && Number(ms) > -200 && Number(ms) < 200 ? '<200 ms (폴링 해상도)' : fmtMs(ms)
export const fmtSec = (ms: number | null | undefined) => ms == null ? '–' : `${(ms / 1000).toFixed(2)} s`
export function clock(iso: string | null | undefined, withMs = true): string {
  if (!iso) return '–'
  const d = new Date(iso)
  const hh = d.toTimeString().slice(0, 8)
  return withMs ? `${hh}.${String(d.getMilliseconds()).padStart(3, '0')}` : hh
}
export const dateTime = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString('ko-KR', { hour12: false }) : '–'

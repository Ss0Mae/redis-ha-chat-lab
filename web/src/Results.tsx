import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Bar, BarChart, CartesianGrid, Legend, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, clock, dateTime, fmt, fmtMs, fmtPromoteMs, type Experiment, type ExperimentRow, type SampleRow, type Summary } from './api'
import { C, marksOf } from './Charts'

const T_ROWS: [string, string][] = [
  ['t0_inject', 'T0 장애 주입'], ['t1_detected', 'T1 장애 감지'], ['t2_promotion_start', 'T2 승격 시작'], ['t3_promoted', 'T3 승격 완료'], ['t3b_switch_master', 'T3b Sentinel +switch-master'],
  ['t4_client_aware', 'T4 클라이언트 인식'], ['t5_first_write_ok', 'T5 첫 쓰기 성공'], ['t6_stable', 'T6 토폴로지 안정화'], ['recoveredAt', '노드 복구 명령'], ['recoveredStableAt', '복구 후 안정화'],
]
const CMP: [string, string, (e: Experiment) => unknown][] = [
  ['구성', 'topology', (e) => e.topology],
  ['시나리오', 'scenario', (e) => e.fault?.scenario ?? '–'],
  ['장애 감지', 'detect', (e) => fmtMs(e.timings.detect_ms as number | null)],
  ['Replica 승격', 'promote', (e) => fmtPromoteMs(e.timings.promote_ms as number | null)],
  ['클라이언트 인식', 'aware', (e) => fmtMs(e.timings.client_aware_ms as number | null)],
  ['클라이언트 재연결', 'reconnect', (e) => fmtMs(e.timings.client_reconnect_ms as number | null)],
  ['서비스 중단', 'outage', (e) => fmtMs(e.timings.outage_ms as number | null)],
  ['토폴로지 안정화', 'stabilize', (e) => fmtMs(e.timings.stabilize_ms as number | null)],
  ['정상 상태 TPS', 'tps-before', (e) => fmt(e.summary.phases?.BEFORE?.tps, 1)],
  ['정상 상태 p95', 'p95-before', (e) => e.summary.phases?.BEFORE ? `${fmt(e.summary.phases.BEFORE.p95_ms_median, 2)} ms` : '–'],
  ['장애 중 오류율', 'err-during', (e) => e.summary.phases?.DURING ? `${fmt(e.summary.phases.DURING.error_rate_pct, 2)} %` : '–'],
  ['장애 중 실패 요청', 'fail-during', (e) => fmt(e.summary.phases?.DURING?.fail)],
  ['정상화 후 TPS', 'tps-after', (e) => fmt(e.summary.phases?.AFTER?.tps, 1)],
  ['승인 쓰기 유실', 'lost', (e) => fmt(e.summary.consistency?.lost_acked_writes as number | undefined)],
  ['유실률', 'loss-pct', (e) => e.summary.consistency?.acked_write_loss_pct == null ? '–' : `${e.summary.consistency.acked_write_loss_pct} %`],
  ['중복 반영 INCR', 'dup', (e) => fmt(e.summary.consistency?.duplicate_incr as number | undefined)],
]
const CONS: [string, string][] = [
  ['acked_writes_before_fault', '장애 전 성공 응답을 받은 쓰기'], ['acked_writes_total', '승인된 쓰기 전체'], ['acked_messages', '승인된 메시지'],
  ['acked_messages_present', '복구 후 남아 있는 승인 메시지'], ['acked_messages_lost', '승인 후 유실된 메시지'], ['last_preserved_seq', '마지막으로 보존된 순번'],
  ['duplicate_stream_entries', '스트림 중복 항목'], ['duplicate_incr', '중복 반영된 INCR'], ['lost_incr', '유실된 INCR'], ['wrong_value_keys', '잘못된 값으로 복구된 키'],
  ['lost_acked_writes', '승인 쓰기 유실 합계'], ['acked_write_loss_pct', '승인 쓰기 유실률 (%)'], ['read_errors', '검증 중 읽기 오류'],
]

export function Results() {
  const list = useQuery({ queryKey: ['experiments'], queryFn: () => api<ExperimentRow[]>('GET', '/api/experiments'), refetchInterval: 5000 })
  const [sel, setSel] = useState<string>('')
  const [cmpA, setCmpA] = useState('')
  const [cmpB, setCmpB] = useState('')
  const id = sel || list.data?.[0]?.id || ''
  const detail = useQuery({ queryKey: ['experiment', id], queryFn: () => api<Experiment>('GET', `/api/experiments/${id}`), enabled: !!id, refetchInterval: 3000 })
  const samples = useQuery({ queryKey: ['samples', id], queryFn: () => api<SampleRow[]>('GET', `/api/experiments/${id}/samples`), enabled: !!id })
  const a = useQuery({ queryKey: ['experiment', cmpA], queryFn: () => api<Experiment>('GET', `/api/experiments/${cmpA}`), enabled: !!cmpA })
  const b = useQuery({ queryKey: ['experiment', cmpB], queryFn: () => api<Experiment>('GET', `/api/experiments/${cmpB}`), enabled: !!cmpB })
  const e = detail.data
  const rows = list.data ?? []

  return (
    <div className="results">
      <section aria-labelledby="list-h">
        <div className="sec-head"><h2 id="list-h">실험 목록</h2><span className="sec-note">최근 200건, MySQL 에 저장된 실험</span></div>
        {rows.length === 0 ? <p className="empty">저장된 실험이 없습니다. 실시간 탭에서 실험을 만들어 장애를 주입하고 종료하면 여기에 쌓입니다.</p> : (
          <div className="tablewrap">
            <table className="list">
              <thead><tr><th>이름</th><th>구성</th><th>상태</th><th>기록</th><th>만든 시각</th><th>T0</th><th>T5</th><th>중단</th><th>유실</th></tr></thead>
              <tbody>
                {rows.map((r) => {
                  const s = parseSummary(r.summary)
                  return (
                    <tr key={r.id} onClick={() => setSel(r.id)} aria-selected={r.id === id} className={r.id === id ? 'sel' : ''}>
                      <td><b>{r.name}</b><br /><small>{r.id}</small></td><td>{r.topology}</td><td>{r.status}</td><td>{r.recordingMode ?? r.recording_mode}</td>
                      <td>{dateTime(r.created_at)}</td><td>{clock(r.t0)}</td><td>{clock(r.t5)}</td>
                      <td>{fmtMs(s?.timings?.outage_ms as number | null)}</td><td>{fmt(s?.consistency?.lost_acked_writes as number | undefined)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {e && (
        <section aria-labelledby="det-h" className="detail">
          <div className="sec-head">
            <h2 id="det-h">{e.name}</h2>
            <span className="sec-note">
              {e.topology}, {e.status}, 기록 {e.recordingMode}
              {e.fault ? `, ${e.fault.scenario}${e.failedNode ? ` (${e.failedNode})` : ''}` : ''}
              {' '}<a href={`/api/experiments/${e.id}`} target="_blank" rel="noreferrer">JSON</a> <a href={`/api/experiments/${e.id}/export?format=csv`} target="_blank" rel="noreferrer">CSV</a>
            </span>
          </div>
          {e.hypothesis && <p className="hypo">가설: {e.hypothesis}</p>}
          {e.workload && <p className="hypo">부하: 초당 {e.workload.rps}건, GET {e.workload.mix.get} SET {e.workload.mix.set} INCR {e.workload.mix.incr} SEND {e.workload.mix.send} MGET {e.workload.mix.mget}, 사용자 {fmt(e.workload.users)}, 방 {e.workload.rooms}
            {Object.keys(e.redisSettings).length ? `, Redis ${Object.entries(e.redisSettings).map(([k, v]) => `${k}=${v}`).join(' ')}` : ''}</p>}

          <div className="detail-grid">
            <div>
              <h3>장애 타임라인</h3>
              <table>
                <thead><tr><th>시각</th><th>절대</th><th>T0 기준</th></tr></thead>
                <tbody>
                  {T_ROWS.map(([k, label]) => {
                    const v = e.timings[k] ? String(e.timings[k]) : null
                    const t0 = e.timings.t0_inject ? Date.parse(String(e.timings.t0_inject)) : null
                    return <tr key={k}><td>{label}</td><td>{clock(v)}</td><td>{v && t0 ? `+${((Date.parse(v) - t0) / 1000).toFixed(3)} s` : '–'}</td></tr>
                  })}
                </tbody>
              </table>
              <table className="derived-t">
                <tbody>
                  {[['detect_ms', '장애 감지 시간 = T1 − T0'], ['promote_ms', 'Replica 승격 시간 = T3 − T2'], ['client_aware_ms', '클라이언트 인식 시간 = T4 − T3'], ['client_reconnect_ms', '클라이언트 재연결 시간 = T5 − T3'], ['outage_ms', '서비스 중단 시간 = T5 − T0'], ['stabilize_ms', '토폴로지 안정화 시간 = T6 − T0'], ['rejoin_ms', '재합류 시간 = 복구 후 안정 − 복구 명령']].map(([k, label]) => (
                    <tr key={k}><td>{label}</td><td>{(k === 'promote_ms' ? fmtPromoteMs : fmtMs)(e.timings[k] as number | null)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div>
              <h3>단계별 요청</h3>
              {e.summary.phases ? (
                <table>
                  <thead><tr><th>단계</th><th>초</th><th>성공</th><th>실패</th><th>오류율</th><th>TPS</th><th>성공 TPS</th><th>p95 중앙값</th><th>p99 중앙값</th></tr></thead>
                  <tbody>
                    {(['BEFORE', 'DURING', 'AFTER'] as const).map((p) => { const s = e.summary.phases?.[p]; return s ? (
                      <tr key={p}><td>{p === 'BEFORE' ? '장애 전' : p === 'DURING' ? '장애 중' : '복구 후'}</td><td>{s.seconds}</td><td>{fmt(s.ok)}</td><td>{fmt(s.fail)}</td><td>{fmt(s.error_rate_pct, 2)} %</td><td>{fmt(s.tps, 1)}</td><td>{fmt(s.success_tps, 1)}</td><td>{fmt(s.p95_ms_median, 2)} ms</td><td>{fmt(s.p99_ms_median, 2)} ms</td></tr>
                    ) : null })}
                  </tbody>
                </table>
              ) : <p className="empty">실험을 종료하면 단계별 집계가 채워집니다.</p>}
              {e.summary.log_timings && Object.keys(e.summary.log_timings).length > 0 && (
                <>
                  <h3>로그 시각</h3>
                  <p className="hypo">노드 로그의 타임스탬프로 잡은 시각입니다. 폴링(200 ms)보다 정확합니다.</p>
                  <table>
                    <thead><tr><th>항목</th><th>시각</th><th>T0 기준</th><th>노드</th></tr></thead>
                    <tbody>
                      {Object.entries(e.summary.log_timings).map(([k, v]) => {
                        const t0 = e.timings.t0_inject ? Date.parse(String(e.timings.t0_inject)) : null
                        return <tr key={k}><td>{k}</td><td>{clock(v.at)}</td><td>{t0 && v.at ? `${((Date.parse(v.at) - t0) / 1000).toFixed(3)} s` : '–'}</td><td>{v.node ?? '–'}</td></tr>
                      })}
                    </tbody>
                  </table>
                </>
              )}
              <h3>데이터 정합성</h3>
              {e.summary.consistency ? (
                <table>
                  <tbody>{CONS.map(([k, label]) => <tr key={k}><td>{label}</td><td>{Array.isArray(e.summary.consistency?.[k]) ? '' : fmt(e.summary.consistency?.[k] as number | undefined, k.endsWith('pct') ? 2 : 0)}</td></tr>)}</tbody>
                </table>
              ) : <p className="empty">{e.summary.consistency_error ?? '실험을 종료하면 승인 쓰기 대조 결과가 채워집니다.'}</p>}
            </div>
          </div>

          <h3>초당 성공, 실패</h3>
          <SampleChart rows={samples.data ?? []} exp={e} />

          <h3>이벤트 기록</h3>
          <div className="tablewrap">
            <table className="events">
              <thead><tr><th>시각</th><th>T0 기준</th><th>출처</th><th>종류</th><th>내용</th></tr></thead>
              <tbody>
                {e.events.map((ev, i) => {
                  const t0 = e.timings.t0_inject ? Date.parse(String(e.timings.t0_inject)) : null
                  return <tr key={i}><td>{clock(ev.at)}</td><td>{t0 ? `${((Date.parse(ev.at) - t0) / 1000).toFixed(3)} s` : ''}</td><td>{ev.source}</td><td>{ev.type}</td><td className="detail">{ev.detail}</td></tr>
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <section aria-labelledby="cmp-h">
        <div className="sec-head"><h2 id="cmp-h">두 실험 비교</h2><span className="sec-note">같은 부하, 같은 시나리오로 Sentinel 과 Cluster 를 나란히</span></div>
        <div className="row cmp-pick">
          <label>A<select value={cmpA} onChange={(ev) => setCmpA(ev.target.value)}><option value="">고르기</option>{rows.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.topology})</option>)}</select></label>
          <label>B<select value={cmpB} onChange={(ev) => setCmpB(ev.target.value)}><option value="">고르기</option>{rows.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.topology})</option>)}</select></label>
        </div>
        {a.data && b.data ? (
          <table className="cmp">
            <thead><tr><th>지표</th><th>{a.data.name}</th><th>{b.data.name}</th></tr></thead>
            <tbody>{CMP.map(([label, k, f]) => <tr key={k}><td>{label}</td><td>{String(f(a.data!))}</td><td>{String(f(b.data!))}</td></tr>)}</tbody>
          </table>
        ) : <p className="empty">두 실험을 고르면 표가 나옵니다.</p>}
      </section>
    </div>
  )
}

function SampleChart({ rows, exp }: { rows: SampleRow[]; exp: Experiment }) {
  const data = useMemo(() => rows.map((r) => ({ t: Date.parse(r.at), ok: r.ok, fail: r.fail })), [rows])
  const marks = marksOf(exp)
  if (data.length === 0) return <p className="empty">표본이 없습니다.</p>
  return (
    <ResponsiveContainer width="100%" height={220}>
      <BarChart data={data} margin={{ top: 12, right: 8, left: 0, bottom: 0 }} barCategoryGap={1}>
        <CartesianGrid vertical={false} stroke="#e4e8ec" />
        <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={(t) => clock(new Date(t).toISOString(), false)} minTickGap={40} stroke="#8a94a0" fontSize={11} />
        <YAxis stroke="#8a94a0" fontSize={11} width={48} />
        <Tooltip labelFormatter={(t) => clock(new Date(Number(t)).toISOString())} />
        <Legend />
        <Bar dataKey="ok" name="성공" stackId="a" fill={C.ok} isAnimationActive={false} />
        <Bar dataKey="fail" name="실패" stackId="a" fill={C.fail} isAnimationActive={false} />
        {marks.map((m) => <ReferenceLine key={m.label} x={m.x} stroke={C.mark} strokeDasharray="3 3" label={{ value: m.label, position: 'top', fontSize: 11 }} />)}
      </BarChart>
    </ResponsiveContainer>
  )
}

/** 목록의 summary 는 예전 컨테이너에선 문자열, 새 컨테이너에선 객체로 온다. 둘 다 받는다. */
function parseSummary(s: string | Summary | null): Summary | null {
  if (!s) return null
  if (typeof s !== 'string') return s
  try { return JSON.parse(s) as Summary } catch { return null }
}

import { useEffect, useState } from 'react'
import { clock, fmtMs, fmtPromoteMs, type Experiment } from './api'

const MARKS: [string, string][] = [
  ['t0_inject', 'T0 주입'], ['t1_detected', 'T1 감지'], ['t2_promotion_start', 'T2 승격 시작'], ['t3_promoted', 'T3 승격 완료'],
  ['t4_client_aware', 'T4 클라이언트 인식'], ['t5_first_write_ok', 'T5 첫 쓰기 성공'], ['t6_stable', 'T6 안정화'],
]
const DERIVED: [string, string][] = [
  ['detect_ms', '감지 (T1−T0)'], ['promote_ms', '승격 (T3−T2)'], ['client_aware_ms', '클라이언트 인식 (T4−T3)'], ['client_reconnect_ms', '재연결 (T5−T3)'],
  ['outage_ms', '서비스 중단 (T5−T0)'], ['stabilize_ms', '안정화 (T6−T0)'], ['rejoin_ms', '재합류 (복구 후 안정까지)'],
]

export function Timeline({ exp }: { exp: Experiment | null }) {
  const t0 = exp?.timings.t0_inject ? Date.parse(String(exp.timings.t0_inject)) : null
  const running = !!t0 && exp?.status === 'FAULT'
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    if (!running) return
    const id = setInterval(() => setNow(Date.now()), 100)
    return () => clearInterval(id)
  }, [running])
  if (!exp || !t0) return <div className="rail empty-rail"><span>장애 주입 전</span></div>

  const marks = MARKS.map(([k, label]) => ({ k, label, at: exp.timings[k] ? Date.parse(String(exp.timings[k])) - t0 : null }))
  const switchAt = exp.timings.t3b_switch_master ? Date.parse(String(exp.timings.t3b_switch_master)) - t0 : null
  const elapsed = running ? now - t0 : null
  const scale = Math.max(10_000, ...marks.map((m) => m.at ?? 0), elapsed ?? 0, switchAt ?? 0) * 1.08
  return (
    <div className="timeline">
      <div className="rail">
        {elapsed != null && <div className="elapsed" style={{ width: `${(elapsed / scale) * 100}%` }}><span>{(elapsed / 1000).toFixed(1)} s 경과</span></div>}
        {switchAt != null && (
          <div className="mark minor" style={{ left: `${(switchAt / scale) * 100}%` }} title="Sentinel +switch-master">
            <i />
            <span>switch-master <small>+{(switchAt / 1000).toFixed(2)} s</small></span>
          </div>
        )}
        {marks.map((m, i) => m.at == null ? null : (
          <div className={'mark tier-' + (i % 4)} style={{ left: `${(m.at / scale) * 100}%` }} key={m.k}>
            <i />
            <span>{m.label.split(' ')[0]} <small>+{(m.at / 1000).toFixed(2)} s</small></span>
          </div>
        ))}
      </div>
      <dl className="derived">
        {DERIVED.map(([k, label]) => <div key={k}><dt>{label}</dt><dd>{(k === 'promote_ms' ? fmtPromoteMs : fmtMs)(exp.timings[k] as number | null)}</dd></div>)}
      </dl>
      <p className="tl-foot">
        T0 {clock(String(exp.timings.t0_inject))}
        {exp.failedNode ? `, 대상 ${exp.failedNode}` : ''}
        {exp.fault ? `, ${exp.fault.scenario}` : ''}
        {exp.timings.recoveredAt ? `, 복구 ${clock(String(exp.timings.recoveredAt))}` : ''}
        {marks.filter((m) => m.at == null).length > 0 && exp.status !== 'FAULT' ? `, 채워지지 않은 시각: ${marks.filter((m) => m.at == null).map((m) => m.label.split(' ')[0]).join(' ')}` : ''}
      </p>
    </div>
  )
}

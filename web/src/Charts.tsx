import { useMemo } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { clock, type Experiment, type LabEvent, type Sample, type Topology } from './api'
import type { LagPoint } from './live'

// 시리즈 색은 고정 순서로만 쓴다(순환 금지). 성공/실패, p95/p99, Replica 별 lag, 오류 종류.
export const C = { ok: '#0f766e', fail: '#d7263d', p95: '#101820', p99: '#6b7280', mark: '#101820' }
export const REPLICA_COLORS = ['#2563eb', '#0891b2', '#7c3aed', '#db2777', '#ca8a04', '#4b5563']
export const SHARD_COLORS = ['#1d4ed8', '#0e7490', '#6d28d9', '#be185d']
export const ERROR_COLORS: Record<string, string> = {
  TIMEOUT: '#ca8a04', CONNECTION: '#d7263d', MOVED: '#7c3aed', ASK: '#db2777', CROSSSLOT: '#0891b2', CLUSTERDOWN: '#101820', READONLY: '#b45309', NOREPLICAS: '#6b7280', OTHER: '#9ca3af',
}
const T_LABELS: [string, string][] = [['t0_inject', 'T0'], ['t1_detected', 'T1'], ['t3_promoted', 'T3'], ['t5_first_write_ok', 'T5'], ['t6_stable', 'T6'], ['recoveredAt', '복구']]

export function marksOf(exp: Experiment | null): { x: number; label: string }[] {
  if (!exp) return []
  return T_LABELS.filter(([k]) => exp.timings[k]).map(([k, label]) => ({ x: Date.parse(String(exp.timings[k])), label }))
}

const tick = (t: number) => clock(new Date(t).toISOString(), false)

export function Charts({ samples, lags, exp, topo }: { samples: Sample[]; lags: LagPoint[]; exp: Experiment | null; topo: Topology | null }) {
  const data = useMemo(() => samples.map((s) => ({ t: Date.parse(s.at), ok: s.ok, fail: s.fail, p95: s.p95Us / 1000, p99: s.p99Us / 1000, ...s.byError, ...s.byShard })), [samples])
  // Cluster 에서만: byShard 키 "0:ok" 의 앞 숫자가 shard 순번(slot 시작 순), 이름은 토폴로지의 primaries[i]
  const shards = useMemo(() => {
    const idx = new Set<number>()
    for (const s of samples) for (const k of Object.keys(s.byShard ?? {})) idx.add(Number(k.split(':')[0]))
    return [...idx].sort((a, b) => a - b).map((i) => {
      const name = topo?.primaries[i]
      const slots = name ? topo?.nodes.find((n) => n.name === name)?.slots : null
      return { i, label: name ? `${name}${slots ? ` (${slots})` : ''}` : `shard ${i}` }
    })
  }, [samples, topo])
  const showShards = topo?.topology === 'cluster' && shards.length > 0
  const errorKeys = useMemo(() => Object.keys(ERROR_COLORS).filter((k) => samples.some((s) => s.byError[k])), [samples])
  const replicaKeys = useMemo(() => (topo?.nodes ?? []).filter((n) => n.role === 'REPLICA').map((n) => n.name), [topo])
  const marks = marksOf(exp)
  const empty = data.length === 0
  return (
    <section aria-labelledby="ch-h">
      <div className="sec-head"><h2 id="ch-h">실시간 지표</h2><span className="sec-note">최근 5분, 1초 집계{topo?.primaries.length ? `, Primary ${topo.primaries.join(', ')}` : ''}</span></div>
      {empty && <p className="empty">아직 표본이 없습니다. 워크로드를 시작하면 1초마다 한 점씩 쌓입니다.</p>}
      <div className="charts">
        <figure>
          <figcaption>초당 성공, 실패</figcaption>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barCategoryGap={1}>
              <CartesianGrid vertical={false} stroke="#e4e8ec" />
              <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} minTickGap={40} stroke="#8a94a0" fontSize={11} />
              <YAxis stroke="#8a94a0" fontSize={11} width={48} />
              <Tooltip labelFormatter={(t) => clock(new Date(Number(t)).toISOString())} />
              <Legend />
              <Bar dataKey="ok" name="성공" stackId="a" fill={C.ok} isAnimationActive={false} />
              <Bar dataKey="fail" name="실패" stackId="a" fill={C.fail} isAnimationActive={false} />
              {marks.map((m) => <ReferenceLine key={m.label} x={m.x} stroke={C.mark} strokeDasharray="3 3" label={{ value: m.label, position: 'top', fontSize: 11 }} />)}
            </BarChart>
          </ResponsiveContainer>
        </figure>
        <figure>
          <figcaption>응답시간 p95, p99 (ms)</figcaption>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid vertical={false} stroke="#e4e8ec" />
              <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} minTickGap={40} stroke="#8a94a0" fontSize={11} />
              <YAxis stroke="#8a94a0" fontSize={11} width={48} />
              <Tooltip labelFormatter={(t) => clock(new Date(Number(t)).toISOString())} formatter={(v) => `${Number(v).toFixed(1)} ms`} />
              <Legend />
              <Line dataKey="p95" name="p95" stroke={C.p95} dot={false} strokeWidth={2} isAnimationActive={false} />
              <Line dataKey="p99" name="p99" stroke={C.p99} dot={false} strokeWidth={2} strokeDasharray="5 3" isAnimationActive={false} />
              {marks.map((m) => <ReferenceLine key={m.label} x={m.x} stroke={C.mark} strokeDasharray="3 3" />)}
            </LineChart>
          </ResponsiveContainer>
        </figure>
        <figure>
          <figcaption>복제 지연, Replica 별 (bytes)</figcaption>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={lags} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid vertical={false} stroke="#e4e8ec" />
              <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} minTickGap={40} stroke="#8a94a0" fontSize={11} />
              <YAxis stroke="#8a94a0" fontSize={11} width={56} />
              <Tooltip labelFormatter={(t) => clock(new Date(Number(t)).toISOString())} />
              <Legend />
              {replicaKeys.map((k, i) => <Line key={k} dataKey={k} name={k} stroke={REPLICA_COLORS[i % REPLICA_COLORS.length]} dot={false} strokeWidth={2} isAnimationActive={false} connectNulls />)}
            </LineChart>
          </ResponsiveContainer>
        </figure>
        <figure>
          <figcaption>오류 종류별 (초당)</figcaption>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barCategoryGap={1}>
              <CartesianGrid vertical={false} stroke="#e4e8ec" />
              <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} minTickGap={40} stroke="#8a94a0" fontSize={11} />
              <YAxis stroke="#8a94a0" fontSize={11} width={48} />
              <Tooltip labelFormatter={(t) => clock(new Date(Number(t)).toISOString())} />
              <Legend />
              {errorKeys.length === 0 && <Bar dataKey="fail" name="실패" fill={C.fail} isAnimationActive={false} />}
              {errorKeys.map((k) => <Bar key={k} dataKey={k} name={k} stackId="e" fill={ERROR_COLORS[k]} isAnimationActive={false} />)}
            </BarChart>
          </ResponsiveContainer>
        </figure>
        {showShards && (
          <figure className="wide">
            <figcaption>slot 범위별 요청량 (초당, Primary 별 성공은 단색, 실패는 빗금)</figcaption>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barCategoryGap={1} barGap={0}>
                <defs>
                  <pattern id="hatch-fail" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                    <rect width="6" height="6" fill="#fde2e4" />
                    <rect width="2.5" height="6" fill={C.fail} />
                  </pattern>
                </defs>
                <CartesianGrid vertical={false} stroke="#e4e8ec" />
                <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={tick} minTickGap={40} stroke="#8a94a0" fontSize={11} />
                <YAxis stroke="#8a94a0" fontSize={11} width={48} />
                <Tooltip labelFormatter={(t) => clock(new Date(Number(t)).toISOString())} />
                <Legend />
                {shards.map((sh) => <Bar key={sh.i + 'ok'} dataKey={`${sh.i}:ok`} name={`${sh.label} 성공`} stackId={`s${sh.i}`} fill={SHARD_COLORS[sh.i % SHARD_COLORS.length]} isAnimationActive={false} />)}
                {shards.map((sh) => <Bar key={sh.i + 'fail'} dataKey={`${sh.i}:fail`} name={`${sh.label} 실패`} stackId={`s${sh.i}`} fill="url(#hatch-fail)" stroke={C.fail} strokeWidth={0.5} isAnimationActive={false} />)}
                {marks.map((m) => <ReferenceLine key={m.label} x={m.x} stroke={C.mark} strokeDasharray="3 3" />)}
              </BarChart>
            </ResponsiveContainer>
          </figure>
        )}
      </div>
    </section>
  )
}

export function EventLog({ events }: { events: LabEvent[] }) {
  const rows = [...events].reverse().slice(0, 80)
  if (rows.length === 0) return <p className="empty">아직 이벤트가 없습니다.</p>
  return (
    <div className="tablewrap">
      <table className="events">
        <thead><tr><th>시각</th><th>출처</th><th>종류</th><th>내용</th></tr></thead>
        <tbody>
          {rows.map((e, i) => (
            <tr key={i} className={'k-' + e.kind}>
              <td>{clock(e.at)}</td><td>{e.source}</td><td>{e.type}</td><td className="detail">{brief(e.payload)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function brief(p: unknown): string {
  if (p == null) return ''
  if (typeof p === 'string') return p
  const s = JSON.stringify(p)
  return s.length > 160 ? s.slice(0, 160) + '…' : s
}

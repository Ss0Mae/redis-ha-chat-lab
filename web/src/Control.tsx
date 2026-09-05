import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api, AUTO_RECOVER_SECONDS, SCENARIOS, type Experiment, type FaultRequest, type Scenario, type Topology, type WorkloadConfig, type WorkloadStatus } from './api'

const DEFAULT_WORKLOAD: WorkloadConfig = { rps: 500, mix: { get: 50, set: 20, incr: 10, send: 15, mget: 5 }, users: 10000, rooms: 100, hashTag: true, mgetSize: 5 }
const SETTINGS: [string, string, string[]][] = [
  ['min-replicas-to-write', 'min-replicas-to-write', ['', '0', '1', '2']],
  ['min-replicas-max-lag', 'min-replicas-max-lag', ['', '1', '5', '10']],
  ['appendonly', 'appendonly', ['', 'no', 'yes']],
  ['appendfsync', 'appendfsync', ['', 'everysec', 'always', 'no']],
]

export function Control({ exp, topo, onChanged }: { exp: Experiment | null; topo: Topology | null; onChanged: () => void }) {
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const say = (ok: boolean, text: string) => setMsg({ ok, text })
  const fail = (e: unknown) => say(false, e instanceof Error ? e.message : String(e))

  // ---- 워크로드 ----
  const [wl, setWl] = useState<WorkloadConfig>(DEFAULT_WORKLOAD)
  const status = useQuery({ queryKey: ['workload'], queryFn: () => api<WorkloadStatus>('GET', '/api/workloads/status'), refetchInterval: 2000 })
  const start = useMutation({ mutationFn: () => api('POST', '/api/workloads/start', wl), onSuccess: () => { say(true, `워크로드 시작, 초당 ${wl.rps}건`); status.refetch() }, onError: fail })
  const stop = useMutation({ mutationFn: () => api('POST', '/api/workloads/stop'), onSuccess: () => { say(true, '워크로드 중지'); status.refetch() }, onError: fail })

  // ---- 실험 ----
  const [name, setName] = useState('')
  const [hypothesis, setHypothesis] = useState('')
  const [mode, setMode] = useState('BATCH')
  const [settings, setSettings] = useState<Record<string, string>>({})
  const [startWorkload, setStartWorkload] = useState(true)
  const [flush, setFlush] = useState(true)
  const create = useMutation({
    mutationFn: () => api<Experiment>('POST', '/api/experiments', {
      name: name || undefined, hypothesis: hypothesis || undefined, recordingMode: mode, workload: wl,
      redisSettings: Object.fromEntries(Object.entries(settings).filter(([, v]) => v !== '')), startWorkload, flush,
    }),
    onSuccess: (e) => { say(true, `실험 ${e.id} 시작`); onChanged(); status.refetch() },
    onError: fail,
  })
  const recover = useMutation({ mutationFn: () => api('POST', `/api/experiments/${exp!.id}/recover`), onSuccess: () => { say(true, '복구 명령 보냄, 노드가 다시 붙는 중'); onChanged() }, onError: fail })
  const finish = useMutation({ mutationFn: () => api<Experiment>('POST', `/api/experiments/${exp!.id}/finish`), onSuccess: (e) => { say(true, `실험 종료, 결과 탭에서 ${e.id} 를 보세요`); onChanged(); status.refetch() }, onError: fail })

  // ---- 장애 ----
  const [scenarioId, setScenarioId] = useState('KILL_PRIMARY')
  const scenario = useMemo(() => SCENARIOS.find((s) => s.id === scenarioId)!, [scenarioId])
  const [target, setTarget] = useState('')
  const [delayMs, setDelayMs] = useState(200)
  const [lossPct, setLossPct] = useState(0)
  const dialog = useRef<HTMLDialogElement>(null)
  const inject = useMutation({
    mutationFn: (req: FaultRequest) => api<Experiment>('POST', `/api/experiments/${exp!.id}/inject-failure`, req),
    onSuccess: (e, req) => { say(true, `${req.scenario} 적용, 대상 ${e.failedNode ?? req.target ?? '전체'}`); onChanged() },
    onError: fail,
  })
  const targets = targetOptions(scenario, topo)
  const buildReq = (): FaultRequest => ({
    scenario: scenario.id, target: target || undefined, confirm: true,
    delayMs: scenario.params.includes('delayMs') ? delayMs : undefined, lossPct: scenario.params.includes('lossPct') ? lossPct : undefined,
  })
  const canInject = !!exp && exp.status !== 'DONE'
  const faultActive = !!exp && exp.status === 'FAULT'

  return (
    <div className="control">
      <h2>실험 제어</h2>
      {msg && <p className={'msg ' + (msg.ok ? 'ok' : 'bad')} role="status">{msg.text}</p>}

      <fieldset>
        <legend>워크로드</legend>
        <div className="row">
          <label>초당 요청<input type="number" min={1} max={5000} value={wl.rps} onChange={(e) => setWl({ ...wl, rps: num(e.target.value, 1) })} /></label>
          <label>사용자<input type="number" min={1} value={wl.users} onChange={(e) => setWl({ ...wl, users: num(e.target.value, 1) })} /></label>
          <label>방<input type="number" min={1} value={wl.rooms} onChange={(e) => setWl({ ...wl, rooms: num(e.target.value, 1) })} /></label>
        </div>
        <div className="row mix">
          {(['get', 'set', 'incr', 'send', 'mget'] as const).map((k) => (
            <label key={k}>{k.toUpperCase()}<input type="number" min={0} value={wl.mix[k]} onChange={(e) => setWl({ ...wl, mix: { ...wl.mix, [k]: num(e.target.value, 0) } })} /></label>
          ))}
        </div>
        <label className="check"><input type="checkbox" checked={wl.hashTag} onChange={(e) => setWl({ ...wl, hashTag: e.target.checked })} /> 방 키에 Hash Tag 사용 (끄면 Cluster 에서 CROSSSLOT)</label>
        <div className="row btns">
          <button type="button" onClick={() => start.mutate()} disabled={start.isPending || status.data?.running}>워크로드 시작</button>
          <button type="button" className="ghost" onClick={() => stop.mutate()} disabled={stop.isPending || !status.data?.running}>중지</button>
          <span className="hint">{status.data?.running ? `실행 중, 보낸 요청 ${status.data.submitted.toLocaleString('ko-KR')}건` : '멈춤'}</span>
        </div>
      </fieldset>

      <fieldset>
        <legend>실험</legend>
        {exp && exp.status !== 'DONE' ? (
          <div className="active-exp">
            <b>{exp.name}</b>
            <span>{exp.status === 'RUNNING' ? '장애 전 측정 중' : exp.status === 'FAULT' ? '장애 진행 중' : exp.status === 'RECOVERED' ? '복구 후 측정 중' : exp.status}</span>
            <small>{exp.id}, 기록 {exp.recordingMode}{Object.keys(exp.redisSettings).length ? `, ${Object.entries(exp.redisSettings).map(([k, v]) => `${k}=${v}`).join(' ')}` : ''}</small>
            <div className="row btns">
              <button type="button" onClick={() => recover.mutate()} disabled={recover.isPending || exp.status !== 'FAULT'}>노드 복구</button>
              <button type="button" className="ghost" onClick={() => finish.mutate()} disabled={finish.isPending}>실험 종료</button>
            </div>
          </div>
        ) : (
          <>
            <label>이름<input value={name} onChange={(e) => setName(e.target.value)} placeholder="예: sentinel-kill-500rps" /></label>
            <label>가설<input value={hypothesis} onChange={(e) => setHypothesis(e.target.value)} placeholder="예: down-after 5초 + 선출 1초, 중단 6~7초" /></label>
            <div className="row">
              <label>기록 방식
                <select value={mode} onChange={(e) => setMode(e.target.value)}>
                  <option value="MEMORY">MEMORY, 초 집계만</option>
                  <option value="BATCH">BATCH, 요청마다 (정합성)</option>
                  <option value="SAMPLED">SAMPLED, 1/100</option>
                </select>
              </label>
            </div>
            <div className="row settings">
              {SETTINGS.map(([k, label, opts]) => (
                <label key={k}>{label}
                  <select value={settings[k] ?? ''} onChange={(e) => setSettings({ ...settings, [k]: e.target.value })}>
                    {opts.map((o) => <option key={o} value={o}>{o === '' ? '그대로' : o}</option>)}
                  </select>
                </label>
              ))}
            </div>
            <label className="check"><input type="checkbox" checked={startWorkload} onChange={(e) => setStartWorkload(e.target.checked)} /> 위 워크로드로 바로 시작</label>
            <label className="check"><input type="checkbox" checked={flush} onChange={(e) => setFlush(e.target.checked)} /> 시작 전에 Redis 비우기</label>
            <div className="row btns">
              <button type="button" onClick={() => create.mutate()} disabled={create.isPending}>실험 만들기</button>
            </div>
          </>
        )}
      </fieldset>

      <fieldset>
        <legend>장애 주입</legend>
        <label>시나리오
          <select value={scenarioId} onChange={(e) => { setScenarioId(e.target.value); setTarget('') }}>
            {[...new Set(SCENARIOS.map((s) => s.group))].map((g) => (
              <optgroup label={g} key={g}>{SCENARIOS.filter((s) => s.group === g).map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</optgroup>
            ))}
          </select>
        </label>
        {scenario.target !== 'none' && (
          <label>대상
            <select value={target} onChange={(e) => setTarget(e.target.value)}>
              {targets.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </label>
        )}
        {scenario.params.length > 0 && (
          <div className="row">
            {scenario.params.includes('delayMs') && <label>지연 ms<input type="number" min={0} max={5000} value={delayMs} onChange={(e) => setDelayMs(num(e.target.value, 0))} /></label>}
            {scenario.params.includes('lossPct') && <label>손실 %<input type="number" min={0} max={50} value={lossPct} onChange={(e) => setLossPct(num(e.target.value, 0))} /></label>}
          </div>
        )}
        <div className="row btns">
          {scenario.danger ? (
            <button type="button" className="danger" disabled={!canInject || inject.isPending || (faultActive && scenario.danger)} onClick={() => dialog.current?.showModal()}>장애 주입</button>
          ) : (
            <button type="button" disabled={!canInject || inject.isPending} onClick={() => inject.mutate(buildReq())}>{scenario.label}</button>
          )}
          {!canInject && <span className="hint">먼저 실험을 만드세요</span>}
          {faultActive && scenario.danger && <span className="hint">장애가 이미 진행 중입니다. 먼저 복구하세요</span>}
        </div>
        <p className="hint">{AUTO_RECOVER_SECONDS}초가 지나면 서버가 스스로 전부 복구합니다. 허용된 시나리오와 카탈로그의 노드 이름만 실행됩니다.</p>
      </fieldset>

      <dialog ref={dialog} className="confirm">
        <h3>정말 실행할까요?</h3>
        <p><b>{scenario.label}</b>{scenario.target !== 'none' ? `, 대상 ${target || targets[0]?.label || '기본'}` : ''}</p>
        <p>실행하면 T0 가 찍히고 워크로드 실패가 시작됩니다. {AUTO_RECOVER_SECONDS}초 뒤 자동 복구, 그 전에는 "노드 복구" 버튼으로 되돌립니다.</p>
        <div className="row btns">
          <button type="button" className="danger" onClick={() => { dialog.current?.close(); inject.mutate(buildReq()) }}>실행</button>
          <button type="button" className="ghost" onClick={() => dialog.current?.close()}>취소</button>
        </div>
      </dialog>
    </div>
  )
}

function targetOptions(s: Scenario, topo: Topology | null): { value: string; label: string }[] {
  const nodes = topo?.nodes ?? []
  const prim = topo?.primaries ?? []
  const named = (pred: (n: Topology['nodes'][number]) => boolean) => nodes.filter(pred).map((n) => ({ value: n.name, label: `${n.name} (${n.ip})` }))
  switch (s.target) {
    case 'primary': return [{ value: '', label: prim.length > 1 ? `primary-1 (${prim[0]})` : `현재 Primary${prim[0] ? ` (${prim[0]})` : ''}` }, ...prim.slice(1).map((p, i) => ({ value: `primary-${i + 2}`, label: `primary-${i + 2} (${p})` }))]
    case 'replica': return [{ value: '', label: 'replica-1' }, { value: 'replica-2', label: 'replica-2' }, { value: 'replica-3', label: 'replica-3' }, ...named((n) => n.role === 'REPLICA')]
    case 'sentinel': return [{ value: '', label: 'sentinel-1' }, { value: 'sentinel-2', label: 'sentinel-2' }, { value: 'sentinel-3', label: 'sentinel-3' }]
    case 'node': return [{ value: '', label: '멈춘 노드 중 첫 번째' }, ...named(() => true)]
    default: return []
  }
}

const num = (v: string, min: number) => Math.max(min, Number(v) || 0)

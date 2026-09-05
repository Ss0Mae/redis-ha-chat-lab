import { useSyncExternalStore } from 'react'
import type { LabEvent, Sample, Topology } from './api'

/**
 * SSE 한 줄(/api/redis/events/stream)을 모듈 단위 저장소에 모은다.
 * topology 는 200 ms 마다 오므로 500 ms 로 묶어 그리고, 그때 Replica 별 lag 도 시계열에 한 점 넣는다.
 * 브라우저 EventSource 가 끊기면 스스로 다시 붙고, 서버는 hello 로 최근 이벤트와 현재 토폴로지를 먼저 보낸다.
 */
export type LagPoint = { t: number; [node: string]: number }
export type LiveState = { connected: boolean; topology: Topology | null; samples: Sample[]; events: LabEvent[]; lags: LagPoint[] }

let state: LiveState = { connected: false, topology: null, samples: [], events: [], lags: [] }
const listeners = new Set<() => void>()
let es: EventSource | null = null
let pendingTopo: Topology | null = null
let topoTimer: number | undefined

function set(patch: Partial<LiveState>) {
  state = { ...state, ...patch }
  listeners.forEach((l) => l())
}

function flushTopology() {
  topoTimer = undefined
  if (!pendingTopo) return
  const topo = pendingTopo
  pendingTopo = null
  const point: LagPoint = { t: Date.parse(topo.at) }
  for (const n of topo.nodes) if (n.role === 'REPLICA') point[n.name] = n.lagBytes
  set({ topology: topo, lags: [...state.lags, point].slice(-600) })
}

function connect() {
  if (es) return
  es = new EventSource('/api/redis/events/stream')
  es.onopen = () => set({ connected: true })
  es.onerror = () => set({ connected: false })
  es.addEventListener('hello', (e) => {
    const d = JSON.parse((e as MessageEvent).data) as { recent: LabEvent[]; topology: Topology }
    set({ connected: true, topology: d.topology, events: d.recent.slice(-300) })
  })
  es.addEventListener('topology', (e) => {
    pendingTopo = (JSON.parse((e as MessageEvent).data) as LabEvent).payload as Topology
    if (topoTimer === undefined) topoTimer = window.setTimeout(flushTopology, 500)
  })
  es.addEventListener('sample', (e) => {
    const s = (JSON.parse((e as MessageEvent).data) as LabEvent).payload as Sample
    set({ samples: [...state.samples, s].slice(-300) })
  })
  for (const kind of ['event', 'fault', 'workload']) {
    es.addEventListener(kind, (e) => {
      const ev = JSON.parse((e as MessageEvent).data) as LabEvent
      set({ events: [...state.events, ev].slice(-300) })
    })
  }
}

function subscribe(l: () => void) {
  listeners.add(l)
  connect()
  return () => { listeners.delete(l) }
}

export function useLive(): LiveState {
  return useSyncExternalStore(subscribe, () => state)
}

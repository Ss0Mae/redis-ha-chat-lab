import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, getToken, setToken, type Experiment } from './api'
import { useLive } from './live'
import { Topology } from './Topology'
import { Timeline } from './Timeline'
import { Control } from './Control'
import { Charts, EventLog } from './Charts'
import { Results } from './Results'

function useHash() {
  const [hash, setHash] = useState(location.hash || '#live')
  useEffect(() => {
    const on = () => setHash(location.hash || '#live')
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  return hash
}

export default function App() {
  const hash = useHash()
  const live = useLive()
  const [token, setTok] = useState(getToken())
  const active = useQuery({
    queryKey: ['active'],
    queryFn: () => api<Experiment | Record<string, never>>('GET', '/api/experiments/active'),
    refetchInterval: 1000,
  })
  const exp = active.data && 'id' in active.data ? (active.data as Experiment) : null
  const topoName = live.topology?.topology ?? '–'

  return (
    <div className="page">
      <header className="masthead">
        <div className="brand">
          <h1>Redis 장애 복구 실험실</h1>
          <p>채팅 워크로드를 흘리면서 Primary 를 끊고, 감지부터 안정화까지를 시각과 건수로 봅니다.</p>
        </div>
        <div className="masthead-right">
          <span className={'badge topo-' + topoName}>{topoName === 'cluster' ? 'Cluster 3P+3R' : topoName.startsWith('sentinel') ? 'Sentinel 1P+2R+3S' : topoName === 'single' ? '단일 Redis' : '연결 대기'}</span>
          <span className={'conn ' + (live.connected ? 'on' : 'off')}>{live.connected ? '실시간 연결됨' : '실시간 끊김, 다시 붙는 중'}</span>
          <label className="token">
            관리자 토큰
            <input value={token} onChange={(e) => { setTok(e.target.value); setToken(e.target.value) }} spellCheck={false} />
          </label>
          <nav className="tabs">
            <a href="#live" aria-current={hash === '#live' ? 'page' : undefined}>실시간</a>
            <a href="#results" aria-current={hash === '#results' ? 'page' : undefined}>실험 결과</a>
          </nav>
        </div>
      </header>

      {hash === '#results' ? (
        <Results />
      ) : (
        <div className="live">
          <main className="live-main">
            <section aria-labelledby="topo-h">
              <div className="sec-head">
                <h2 id="topo-h">토폴로지</h2>
                <span className="sec-note">{live.topology ? (live.topology.stable ? '안정 상태' : '변동 중') : ''}{live.topology?.primaries.length ? `, 현재 Primary ${live.topology.primaries.join(', ')}` : ''}</span>
              </div>
              <Topology topo={live.topology} />
            </section>
            <section aria-labelledby="tl-h">
              <div className="sec-head">
                <h2 id="tl-h">Failover 타임라인</h2>
                <span className="sec-note">{exp ? `${exp.name} (${exp.status})` : '실험이 없습니다. 오른쪽에서 실험을 만들고 장애를 주입하면 T0부터 채워집니다.'}</span>
              </div>
              <Timeline exp={exp} />
            </section>
            <Charts samples={live.samples} lags={live.lags} exp={exp} topo={live.topology} />
            <section aria-labelledby="ev-h">
              <div className="sec-head"><h2 id="ev-h">이벤트</h2><span className="sec-note">Sentinel pubsub, Cluster 상태 전이, Lettuce 연결, 장애 주입</span></div>
              <EventLog events={live.events} />
            </section>
          </main>
          <aside className="live-side">
            <Control exp={exp} topo={live.topology} onChanged={() => active.refetch()} />
          </aside>
        </div>
      )}
    </div>
  )
}

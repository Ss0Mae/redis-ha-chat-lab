import { clock, fmt, type NodeState, type Topology as Topo } from './api'

const STATUS: Record<string, { glyph: string; label: string }> = {
  UP: { glyph: '●', label: '정상' }, DOWN: { glyph: '✕', label: '중단' }, SUSPECTED: { glyph: '?', label: '의심' },
  FAILOVER: { glyph: '↻', label: '승격 중' }, SYNCING: { glyph: '⟳', label: '동기화 중' },
}
const ROLE: Record<string, string> = { PRIMARY: 'Primary', REPLICA: 'Replica', SENTINEL: 'Sentinel' }

function slotStart(n: NodeState) { const m = /^(\d+)/.exec(n.slots ?? ''); return m ? Number(m[1]) : 1 << 20 }

export function Topology({ topo }: { topo: Topo | null }) {
  if (!topo) return <p className="empty">토폴로지를 아직 받지 못했습니다. 앱(8085)이 떠 있는지 확인하세요.</p>
  const redis = topo.nodes.filter((n) => n.role !== 'SENTINEL')
  const sentinels = topo.nodes.filter((n) => n.role === 'SENTINEL')
  const primaries = redis.filter((n) => n.role === 'PRIMARY').sort((a, b) => slotStart(a) - slotStart(b) || a.name.localeCompare(b.name))
  const groups = primaries.map((p) => ({ p, replicas: redis.filter((r) => r.role === 'REPLICA' && r.masterOf === p.name) }))
  const placed = new Set(groups.flatMap((g) => [g.p.name, ...g.replicas.map((r) => r.name)]))
  const others = redis.filter((n) => !placed.has(n.name))
  return (
    <div className={'topo ' + topo.topology}>
      {groups.map((g) => (
        <div className="shard" key={g.p.name}>
          <NodeBlock n={g.p} />
          <div className="replicas">{g.replicas.map((r) => <NodeBlock n={r} key={r.name} />)}</div>
        </div>
      ))}
      {others.length > 0 && (
        <div className="shard">
          <div className="replicas orphans">{others.map((n) => <NodeBlock n={n} key={n.name} />)}</div>
        </div>
      )}
      {sentinels.length > 0 && (
        <div className="sentinels">
          {sentinels.map((s) => <NodeBlock n={s} key={s.name} />)}
          <p className="sentinel-note">
            Sentinel 은 요청을 중계하지 않습니다. Primary 를 감시하고, 죽으면 Replica 를 승격하고, 앱에 새 주소를 알려 줍니다.
            {topo.info.quorum != null ? ` quorum ${String(topo.info.quorum)}, 살아 있는 Sentinel ${String(topo.info.sentinelsUp)}` : ''}
          </p>
        </div>
      )}
    </div>
  )
}

function NodeBlock({ n }: { n: NodeState }) {
  const st = STATUS[n.status] ?? { glyph: '·', label: n.status }
  const role = ROLE[n.role] ?? n.role
  const ex = n.extra ?? {}
  return (
    <article className={`node role-${n.role.toLowerCase()} st-${n.status.toLowerCase()}`} aria-label={`${n.name} ${role} ${st.label}`}>
      <header>
        <b>{n.name}</b>
        <span className="addr">{n.ip}:{n.port}</span>
      </header>
      <div className="role-line">
        <span className="role">{role}</span>
        <span className="st"><i aria-hidden="true">{st.glyph}</i> {st.label}</span>
      </div>
      <dl>
        {n.masterOf && <><dt>복제 원본</dt><dd>{n.masterOf}</dd></>}
        {n.slots && <><dt>slot</dt><dd>{n.slots}</dd></>}
        {n.role !== 'SENTINEL' && n.status !== 'DOWN' && <><dt>offset</dt><dd>{fmt(n.replOffset)}</dd></>}
        {n.role === 'REPLICA' && n.status !== 'DOWN' && <><dt>lag</dt><dd>{fmt(n.lagBytes)} B</dd></>}
        {n.linkStatus && <><dt>링크</dt><dd>{n.linkStatus}</dd></>}
        {typeof ex.connected_slaves === 'string' && n.role === 'PRIMARY' && <><dt>Replica 수</dt><dd>{ex.connected_slaves}</dd></>}
        {typeof ex.config_epoch === 'string' && ex.config_epoch !== '' && <><dt>epoch</dt><dd>{ex.config_epoch}</dd></>}
        {typeof ex.cluster_state === 'string' && ex.cluster_state !== '' && <><dt>cluster</dt><dd>{ex.cluster_state}</dd></>}
        <dt>변경</dt><dd>{clock(n.lastChange, false)}</dd>
      </dl>
    </article>
  )
}

// 채팅 워크로드를 HTTP 로 보낸다. 개방형 모델(constant-arrival-rate)이라 장애 중에도 요청률이 유지된다.
// 단계(warmup/before/during/after)는 시간대별 시나리오로 나누고, 임계값을 걸어 단계별 부분 지표를 요약에 남긴다.
// env: RPS, BASE, USERS, ROOMS, WARM, STEADY, FAULT, RECOVER(초), OUT(요약 파일)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const RPS = +__ENV.RPS || 500;
const BASE = __ENV.BASE || 'http://localhost:8085';
const USERS = +__ENV.USERS || 10000, ROOMS = +__ENV.ROOMS || 100;
const W = +__ENV.WARM || 60, S = +__ENV.STEADY || 120, F = +__ENV.FAULT || 120, R = +__ENV.RECOVER || 120;
const PHASES = { warmup: [0, W], before: [W, S], during: [W + S, F], after: [W + S + F, R] };

function stage(name, start, dur) {
  return { executor: 'constant-arrival-rate', rate: RPS, timeUnit: '1s', duration: dur + 's', startTime: start + 's',
    preAllocatedVUs: Math.max(20, Math.ceil(RPS / 4)), maxVUs: Math.max(200, RPS * 2), exec: 'op', tags: { phase: name }, gracefulStop: '2s' };
}
const scenarios = {}, thresholds = {};
for (const [name, [start, dur]] of Object.entries(PHASES)) {
  if (dur <= 0) continue;
  scenarios[name] = stage(name, start, dur);
  thresholds[`http_req_duration{phase:${name}}`] = ['p(95)<600000'];
  thresholds[`http_reqs{phase:${name}}`] = ['count>=0'];
  thresholds[`checks{phase:${name}}`] = ['rate>=0'];
  for (const o of ['OK', 'TIMEOUT', 'CONNECTION', 'CLUSTERDOWN', 'MOVED', 'ASK', 'READONLY', 'NOREPLICAS', 'OTHER', 'HTTP_ERR'])
    thresholds[`outcomes{phase:${name},outcome:${o}}`] = ['count>=0'];
  for (const op of ['GET', 'SET', 'INCR', 'SEND', 'MGET']) thresholds[`http_req_duration{phase:${name},op:${op}}`] = ['p(95)<600000'];
}
export const options = { scenarios, thresholds, summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'], discardResponseBodies: false };

const outcomes = new Counter('outcomes');
const params = (op) => ({ tags: { op }, timeout: '5s' });

export function op() {
  const r = Math.random() * 100;
  const u = 1 + Math.floor(Math.random() * USERS), room = 1 + Math.floor(Math.random() * ROOMS);
  let res;
  if (r < 50) res = http.get(`${BASE}/api/chat/users/${u}/presence`, params('GET'));
  else if (r < 70) res = http.put(`${BASE}/api/chat/users/${u}/presence`, null, params('SET'));
  else if (r < 80) res = http.post(`${BASE}/api/chat/users/${u}/unread`, null, params('INCR'));
  else if (r < 95) res = http.post(`${BASE}/api/chat/rooms/${room}/messages`, null, params('SEND'));
  else res = http.get(`${BASE}/api/chat/rooms/${room}/presence`, params('MGET'));
  let outcome = 'HTTP_ERR';
  try { const j = res.json(); if (j && j.outcome) outcome = j.outcome; } catch (e) { /* 연결 실패 등 */ }
  outcomes.add(1, { outcome });
  check(res, { ok: (x) => x.status === 200 });
}

export function handleSummary(data) {
  return { [__ENV.OUT || 'results/k6-summary.json']: JSON.stringify(data, null, 1) };
}

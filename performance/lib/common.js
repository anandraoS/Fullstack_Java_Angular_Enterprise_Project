// Shared helpers for the k6 scripts. Credentials come from the environment only:
//   k6 run -e BASE_URL=http://localhost:18080 -e OMS_DEMO_PASSWORD=... performance/order-flow.js
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:18080').replace(/\/$/, '');
const PASSWORD = __ENV.OMS_DEMO_PASSWORD;

export function requirePassword() {
  if (!PASSWORD) {
    fail('OMS_DEMO_PASSWORD is not set (pass with -e OMS_DEMO_PASSWORD=...; run-local.sh prints it)');
  }
}

// Unique per VU + iteration + time + random; satisfies the contract pattern [A-Za-z0-9_-]{8,64}.
export function idempotencyKey(prefix = 'k6') {
  const rnd = Math.random().toString(36).slice(2, 10);
  return `${prefix}-${__VU}-${__ITER}-${Date.now().toString(36)}-${rnd}`.slice(0, 64);
}

export function jsonHeaders(token, extra = {}) {
  return Object.assign(
    { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: `Bearer ${token}` },
    extra,
  );
}

// Returns { token, customerId, roles }. Fails the iteration when login is not 200.
export function login(username) {
  requirePassword();
  const res = http.post(
    `${BASE_URL}/api/v1/auth/token`,
    JSON.stringify({ username, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
  );
  const ok = check(res, { 'login 200': (r) => r.status === 200 });
  if (!ok) {
    fail(`login failed for ${username}: HTTP ${res.status}`);
  }
  const body = res.json();
  return { token: body.accessToken, customerId: body.customerId, roles: body.roles };
}

export const TERMINAL = ['FULFILLED', 'REJECTED', 'CANCELLED', 'FAILED'];

// 4xx that are legitimate outcomes under load (stock exhausted, rate limited) must not count as http_req_failed.
export const expectedStatuses = http.expectedStatuses({ min: 200, max: 399 }, 404, 409, 422, 429);

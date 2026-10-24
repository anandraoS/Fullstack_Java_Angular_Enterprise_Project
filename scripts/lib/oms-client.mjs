// Tiny HTTP client + helpers shared by the live-verification scripts (Node 18+, global fetch; no dependencies).
// Env: OMS_BASE_URL (gateway, default http://localhost:18080), OMS_DEMO_PASSWORD (required for login()).
// The password and tokens are never printed.
import crypto from 'node:crypto';

export const BASE = (process.env.OMS_BASE_URL ?? 'http://localhost:18080').replace(/\/$/, '');
export const CUSTOMER_1 = 'c0000000-0000-0000-0000-000000000001';
export const CUSTOMER_2 = 'c0000000-0000-0000-0000-000000000002';
export const CUSTOMER_3 = 'c0000000-0000-0000-0000-000000000003';
export const sku = (n) => `a0000000-0000-0000-0000-0000000010${String(n).padStart(2, '0')}`; // SKU-10NN product id
export const TERMINAL = new Set(['FULFILLED', 'REJECTED', 'CANCELLED', 'FAILED']);
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const addr = (city = 'Springfield') => ({ line1: '1 Test Street', city, postalCode: '12345', country: 'US' });

export async function call(method, path, { token, body, headers = {}, base = BASE, raw } = {}) {
  const started = Date.now();
  const res = await fetch(base + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined || raw !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'X-Correlation-Id': crypto.randomUUID(),
      ...headers,
    },
    body: raw !== undefined ? raw : body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20000),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, headers: res.headers, json, text, ms: Date.now() - started };
}

export async function login(username, password = process.env.OMS_DEMO_PASSWORD) {
  if (!password) throw new Error('OMS_DEMO_PASSWORD is not set');
  const r = await call('POST', '/api/v1/auth/token', { body: { username, password } });
  if (r.status !== 200) throw new Error(`login ${username} failed: HTTP ${r.status}`);
  return { token: r.json.accessToken, customerId: r.json.customerId, roles: r.json.roles };
}

export function orderBody(customerId, items, city = 'Springfield') {
  return { customerId, items: items.map(([n, q]) => ({ productId: sku(n), quantity: q })), shippingAddress: addr(city) };
}

export async function placeOrder(token, body, key = `it-${crypto.randomUUID()}`) {
  const r = await call('POST', '/api/v1/orders', { token, body, headers: { 'Idempotency-Key': key } });
  return { ...r, key };
}

export async function waitForStatus(token, orderId, want, timeoutS = 90) {
  const wanted = want instanceof Set ? want : new Set(Array.isArray(want) ? want : [want]);
  const deadline = Date.now() + timeoutS * 1000;
  let last = null;
  while (Date.now() < deadline) {
    const r = await call('GET', `/api/v1/orders/${orderId}`, { token });
    last = r.json;
    if (last && wanted.has(last.status)) return last;
    if (last && TERMINAL.has(last.status) && !wanted.has(last.status)) return last; // terminal but not what we wanted
    await sleep(500);
  }
  return last;
}

export function makeChecker() {
  const c = { failures: 0, passes: 0 };
  c.check = (ok, name, detail = '') => {
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' - ' + detail : ''}`);
    if (ok) c.passes++; else c.failures++;
    return ok;
  };
  return c;
}

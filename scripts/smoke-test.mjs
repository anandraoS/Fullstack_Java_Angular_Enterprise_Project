// End-to-end smoke test through the gateway: auth -> products -> create order (idempotent) -> poll until FULFILLED.
// Usage: OMS_DEMO_PASSWORD=... node scripts/smoke-test.mjs        (env: OMS_BASE_URL default http://localhost:18080,
//        OMS_SMOKE_USER default customer1, OMS_SMOKE_TIMEOUT_S default 90)
// Requires Node 18+ (global fetch). The password is read from the environment only and never printed.
import crypto from 'node:crypto';

const BASE = (process.env.OMS_BASE_URL ?? 'http://localhost:18080').replace(/\/$/, '');
const USER = process.env.OMS_SMOKE_USER ?? 'customer1';
const PASS = process.env.OMS_DEMO_PASSWORD;
const TIMEOUT_S = Number(process.env.OMS_SMOKE_TIMEOUT_S ?? 90);
if (!PASS) {
  console.error('OMS_DEMO_PASSWORD is not set (run-local.sh prints it; CI exports it from --emit-env).');
  process.exit(2);
}

let failures = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const check = (ok, name, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' - ' + detail : ''}`);
  if (!ok) failures++;
  return ok;
};
async function call(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'X-Correlation-Id': crypto.randomUUID(),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON body */ }
  return { status: res.status, headers: res.headers, json };
}
function bail(msg) {
  check(false, msg);
  console.log(`\nSMOKE FAILED (${failures} failure(s))`);
  process.exit(1);
}

// 0. unauthenticated request is rejected
{
  const r = await call('GET', '/api/v1/products');
  check(r.status === 401, 'unauthenticated GET /products -> 401', `got ${r.status}`);
}

// 1. login
const login = await call('POST', '/api/v1/auth/token', { body: { username: USER, password: PASS } });
if (!check(login.status === 200 && login.json?.accessToken, `login as ${USER}`, `status ${login.status}`)) bail('cannot continue without a token');
const token = login.json.accessToken;
const customerId = login.json.customerId;
check(Boolean(customerId), 'token response carries customerId');

// 2. browse products, pick one with stock
const products = await call('GET', '/api/v1/products?size=20&inStock=true', { token });
check(products.status === 200 && Array.isArray(products.json?.content), 'list products', `status ${products.status}`);
const pick = (products.json?.content ?? []).find((p) => (p.available ?? 0) >= 2) ?? products.json?.content?.[0];
if (!pick) bail('no product available to order');

// 3. create order (202 + Location), idempotent replay
const key = `smoke-${crypto.randomUUID()}`;
const order = {
  customerId,
  items: [{ productId: pick.id, quantity: 1 }],
  shippingAddress: { line1: '1 Test Street', city: 'Springfield', postalCode: '12345', country: 'US' },
};
const created = await call('POST', '/api/v1/orders', { token, body: order, headers: { 'Idempotency-Key': key } });
if (!check(created.status === 202 && created.json?.id, 'create order -> 202', `status ${created.status} ${created.json?.code ?? ''}`)) bail('order creation failed');
const orderId = created.json.id;
const replay = await call('POST', '/api/v1/orders', { token, body: order, headers: { 'Idempotency-Key': key } });
check(replay.status === 202 && replay.json?.id === orderId, 'idempotent replay returns the same order', `status ${replay.status}`);
check(replay.headers.get('idempotent-replay') === 'true', 'replay carries Idempotent-Replay: true');
const missing = await call('POST', '/api/v1/orders', { token, body: order });
check(missing.status === 400, 'missing Idempotency-Key -> 400', `got ${missing.status}`);

// 4. poll until terminal
const terminal = new Set(['FULFILLED', 'REJECTED', 'CANCELLED', 'FAILED']);
const deadline = Date.now() + TIMEOUT_S * 1000;
let status = created.json.status;
while (Date.now() < deadline) {
  const r = await call('GET', `/api/v1/orders/${orderId}`, { token });
  status = r.json?.status ?? `HTTP ${r.status}`;
  if (terminal.has(status)) break;
  await sleep(1000);
}
check(status === 'FULFILLED', `order ${orderId} reaches FULFILLED`, `last status ${status}`);

// 5. history shows the path
const hist = await call('GET', `/api/v1/orders/${orderId}/history`, { token });
check(hist.status === 200, 'status history readable', `status ${hist.status}`);

console.log(failures === 0 ? '\nSMOKE PASSED' : `\nSMOKE FAILED (${failures} failure(s))`);
process.exit(failures === 0 ? 0 : 1);

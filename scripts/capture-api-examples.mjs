// Captures REAL request/response pairs from the running stack (through the gateway) and writes docs/api/examples.md.
//   OMS_DEMO_PASSWORD=... [OMS_JWT_SECRET=... for a genuinely expired token] node scripts/capture-api-examples.mjs [--out docs/api/examples.md]
// Redaction: Authorization headers are written as "Bearer <redacted>", the accessToken field of the login response as
// "<redacted>"; nothing else secret is ever in a response (emails are never returned by the notification API). Long arrays are
// shortened for readability (the omission is marked). Order creation uses fresh Idempotency-Keys, so the script is re-runnable.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, CUSTOMER_1, call, login, orderBody, placeOrder, sku, sleep, waitForStatus } from './lib/oms-client.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outIdx = process.argv.indexOf('--out');
const OUT = path.resolve(ROOT, outIdx > 0 ? process.argv[outIdx + 1] : 'docs/api/examples.md');

const c1 = await login('customer1');
const c2 = await login('customer2');
const op = (await login('operator1')).token;
const ad = (await login('admin1')).token;
const KEEP_HEADERS = ['content-type', 'location', 'idempotent-replay', 'retry-after', 'www-authenticate', 'x-correlation-id'];

const redactJson = (v) => {
  if (Array.isArray(v)) {
    const arr = v.slice(0, 2).map(redactJson);
    if (v.length > 2) arr.push(`... ${v.length - 2} more item(s) omitted`);
    return arr;
  }
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, k === 'accessToken' ? '<redacted>' : redactJson(x)]));
  return v;
};

const sections = [];
let n = 0;
async function capture(title, { method, path: p, token, body, headers = {}, note, raw, tokenLabel, send } = {}) {
  const res = await (send ?? (() => call(method, p, { token, body, headers, raw })))();
  n++;
  const reqHeaders = { ...headers };
  const lines = [];
  lines.push(`### ${n}. ${title}`, '');
  if (note) lines.push(note, '');
  lines.push('Request', '', '```http', `${method} ${p}`);
  if (token || tokenLabel) lines.push(`Authorization: Bearer ${tokenLabel ?? '<redacted>'}`);
  if (body !== undefined || raw !== undefined) lines.push('Content-Type: application/json');
  for (const [k, v] of Object.entries(reqHeaders)) lines.push(`${k}: ${v}`);
  if (body !== undefined) lines.push('', JSON.stringify(redactJson(body), null, 2));
  if (raw !== undefined) lines.push('', `<${raw.length} bytes of JSON, truncated here>`);
  lines.push('```', '', `Response: **HTTP ${res.status}**`, '', '```http');
  for (const h of KEEP_HEADERS) { const v = res.headers.get(h); if (v) lines.push(`${h}: ${v}`); }
  if (res.json !== null) lines.push('', JSON.stringify(redactJson(res.json), null, 2));
  else if (res.text) lines.push('', res.text.slice(0, 400));
  lines.push('```', '');
  sections.push(lines.join('\n'));
  return res;
}

// minimal HS256 JWT minting for the negative-auth examples (never uses the real secret unless OMS_JWT_SECRET is provided)
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const mint = (secret, claims, alg = 'HS256') => {
  const head = b64({ alg, typ: 'JWT' });
  const payload = b64(claims);
  const sig = crypto.createHmac('sha256', secret).update(`${head}.${payload}`).digest('base64url');
  return `${head}.${payload}.${sig}`;
};
const now = Math.floor(Date.now() / 1000);
const claims = (exp) => ({ sub: 'customer1', roles: ['CUSTOMER'], customerId: CUSTOMER_1, iss: 'oms-dev-issuer', aud: 'oms-api', iat: exp - 3600, exp, jti: crypto.randomUUID() });

// ---------------------------------------------------------------- 1 auth
const loginRes = await capture('Login (dev token endpoint, gateway profile `dev` only)', { method: 'POST', path: '/api/v1/auth/token', body: { username: 'customer1', password: '<OMS_DEMO_PASSWORD>' }, send: () => call('POST', '/api/v1/auth/token', { body: { username: 'customer1', password: process.env.OMS_DEMO_PASSWORD } }), note: 'The password is the per-run random value printed by `scripts/run-local.sh`; it is replaced by a placeholder below.' });
await capture('Login with a wrong password: 401', { method: 'POST', path: '/api/v1/auth/token', body: { username: 'customer1', password: 'wrong-password' } });

// ---------------------------------------------------------------- 2 orders
const key = `ex-${crypto.randomUUID()}`;
const okBody = orderBody(CUSTOMER_1, [[1, 2]]);
const created = await capture('Create order: success (202 Accepted)', { method: 'POST', path: '/api/v1/orders', token: c1.token, body: okBody, headers: { 'Idempotency-Key': key }, note: 'Validation and the saga run asynchronously, so the order is returned in status `CREATED` with `totalAmount` 0.00 and unpriced items (contract section 13).' });
await capture('Idempotent replay (same key, same body): 202 + `Idempotent-Replay: true`', { method: 'POST', path: '/api/v1/orders', token: c1.token, body: okBody, headers: { 'Idempotency-Key': key }, note: 'Returns the ORIGINAL snapshot (status `CREATED`), not the order\'s current state.' });
await capture('Idempotency key reused with a different body: 422', { method: 'POST', path: '/api/v1/orders', token: c1.token, body: orderBody(CUSTOMER_1, [[1, 3]]), headers: { 'Idempotency-Key': key } });
await capture('Missing Idempotency-Key: 400 `IDEMPOTENCY_KEY_REQUIRED`', { method: 'POST', path: '/api/v1/orders', token: c1.token, body: okBody });
await capture('Validation error: 400 `VALIDATION_FAILED`', { method: 'POST', path: '/api/v1/orders', token: c1.token, body: { customerId: CUSTOMER_1, items: [{ productId: sku(1), quantity: 0 }], shippingAddress: { line1: '', city: 'Springfield', postalCode: '12345', country: 'US' } }, headers: { 'Idempotency-Key': `ex-${crypto.randomUUID()}` } });
const orderId = created.json?.id;
await waitForStatus(c1.token, orderId, 'FULFILLED');
await capture('Get order after the saga finished (FULFILLED)', { method: 'GET', path: `/api/v1/orders/${orderId}`, token: c1.token });
await capture('Order status history', { method: 'GET', path: `/api/v1/orders/${orderId}/history`, token: c1.token });
await capture('Cross-customer access: 404 (not 403, to avoid enumeration)', { method: 'GET', path: `/api/v1/orders/${orderId}`, token: c2.token });
await capture('Cancel a FULFILLED order: 409 `INVALID_STATE_TRANSITION`', { method: 'POST', path: `/api/v1/orders/${orderId}/cancel`, token: c1.token, body: { reason: 'too late' } });

// insufficient stock
const rej = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[20, 1]]));
await capture('Insufficient stock, step 1: accepted (202)', { method: 'POST', path: '/api/v1/orders', token: c1.token, body: orderBody(CUSTOMER_1, [[20, 1]]), send: async () => rej, headers: { 'Idempotency-Key': rej.key } });
await waitForStatus(c1.token, rej.json.id, 'REJECTED');
await capture('Insufficient stock, step 2: the order is REJECTED (SKU-1020 has stock 0)', { method: 'GET', path: `/api/v1/orders/${rej.json.id}`, token: c1.token });
await capture('Customer creating an order for another customer: 403', { method: 'POST', path: '/api/v1/orders', token: c2.token, body: okBody, headers: { 'Idempotency-Key': `ex-${crypto.randomUUID()}` } });

// cancel success: place, wait for CONFIRMED (or any non terminal), cancel
const toCancel = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[5, 1]]));
await waitForStatus(c1.token, toCancel.json.id, ['VALIDATED', 'INVENTORY_RESERVED', 'CONFIRMED']);
await capture('Cancel an in-flight order: success (200)', { method: 'POST', path: `/api/v1/orders/${toCancel.json.id}/cancel`, token: c1.token, body: { reason: 'Ordered by mistake' }, note: 'Allowed from CREATED, VALIDATED, INVENTORY_RESERVED and CONFIRMED; a CONFIRMED order passes through COMPENSATION_PENDING while the reservation is released.' });
await waitForStatus(c1.token, toCancel.json.id, 'CANCELLED');

// FAILTOWN
const ft = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[4, 1]], 'FAILTOWN'));
const ftf = await waitForStatus(c1.token, ft.json.id, 'FAILED', 120);
await capture('Failed fulfillment (shipping city `FAILTOWN`) ends FAILED, stock released', { method: 'GET', path: `/api/v1/orders/${ft.json.id}`, token: c1.token, note: `Final state reached ${ftf?.status}; the history passes CONFIRMED > COMPENSATION_PENDING > FAILED.` });
await capture('Failed order status history', { method: 'GET', path: `/api/v1/orders/${ft.json.id}/history`, token: c1.token });

// ---------------------------------------------------------------- 3 catalog / inventory / staff
await capture('Customer product list (paged, availability only)', { method: 'GET', path: '/api/v1/products?size=2&sort=sku,asc', token: c1.token });
await capture('Operator inventory item (stock fields visible)', { method: 'GET', path: `/api/v1/inventory/${sku(19)}`, token: op });
await capture('Customer cannot read inventory: 403', { method: 'GET', path: '/api/v1/inventory', token: c1.token });
await capture('Stock adjustment below reserved/on-hand: 409 `CONFLICT`', { method: 'PUT', path: `/api/v1/inventory/${sku(19)}/stock`, token: op, body: { delta: -100000, reason: 'invalid adjustment demo' } });
await capture('Saga of an order (operator)', { method: 'GET', path: `/api/v1/admin/saga/${ft.json.id}`, token: op });
await capture('Retry of a terminal order: 409', { method: 'POST', path: `/api/v1/admin/orders/${ft.json.id}/retry`, token: op });
await capture('Failed-orders list (operator)', { method: 'GET', path: '/api/v1/admin/orders/failed?size=2', token: op });
await capture('Fulfillment of the failed order (operator)', { method: 'GET', path: `/api/v1/fulfillments?orderId=${ft.json.id}`, token: op });
await capture('Notifications of the order (customer, own only; recipient address never returned)', { method: 'GET', path: `/api/v1/notifications?orderId=${orderId}&size=2`, token: c1.token });
await capture('Audit entries of an order (operator)', { method: 'GET', path: `/api/v1/ops/order/audit?entityType=Order&entityId=${orderId}&size=2`, token: op });
await capture('Messaging status through the gateway rewrite (operator)', { method: 'GET', path: '/api/v1/ops/order/messaging', token: op });
await capture('Aggregate health (operator)', { method: 'GET', path: '/api/v1/ops/health', token: op });
await capture('Reconciliation (admin)', { method: 'POST', path: '/api/v1/admin/reconciliation/run', token: ad });
await capture('Reconciliation as OPERATOR: 403', { method: 'POST', path: '/api/v1/admin/reconciliation/run', token: op });
await capture('Internal endpoints are not routed through the gateway: 404', { method: 'POST', path: '/api/v1/internal/products/validate', token: ad, body: { items: [] } });

// ---------------------------------------------------------------- 4 auth errors
await capture('No token: 401', { method: 'GET', path: '/api/v1/orders' });
await capture('Forged token (signed with a random secret): 401', { method: 'GET', path: '/api/v1/orders', token: 'x', tokenLabel: '<forged JWT>', send: () => call('GET', '/api/v1/orders', { token: mint(crypto.randomBytes(32), claims(now + 3600)) }) });
if (process.env.OMS_JWT_SECRET) {
  await capture('Expired token (correct signature, `exp` in the past): 401', { method: 'GET', path: '/api/v1/orders', token: 'x', tokenLabel: '<expired JWT>', send: () => call('GET', '/api/v1/orders', { token: mint(process.env.OMS_JWT_SECRET, claims(now - 600)) }) });
} else sections.push('### (skipped) Expired token\n\nSet `OMS_JWT_SECRET` (the stack\'s per-run secret) to mint a genuinely expired, correctly signed token; it was not available in this capture.\n');
await capture('Algorithm `none` token: 401', { method: 'GET', path: '/api/v1/orders', token: 'x', tokenLabel: '<alg=none JWT>', send: () => call('GET', '/api/v1/orders', { token: `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${b64(claims(now + 3600))}.` }) });

// ---------------------------------------------------------------- 5 limits
const big = JSON.stringify({ customerId: CUSTOMER_1, items: [{ productId: sku(1), quantity: 1 }], shippingAddress: { line1: 'x'.repeat(70000), city: 'a', postalCode: '1', country: 'US' } });
await capture('Payload larger than 64 KB: 413 `PAYLOAD_TOO_LARGE`', { method: 'POST', path: '/api/v1/orders', token: c1.token, raw: big, headers: { 'Idempotency-Key': `ex-${crypto.randomUUID()}` } });

// 429: send valid one-unit POSTs back-to-back as operator until the per-subject bucket (capacity 20, refill 5/s) is empty
let limited = null;
for (let i = 0; i < 80 && !limited; i++) {
  const r = await call('POST', '/api/v1/orders', { token: op, body: orderBody(CUSTOMER_1, [[16, 1]]), headers: { 'Idempotency-Key': `ex-${crypto.randomUUID()}` } });
  if (r.status === 429) limited = r;
}
if (limited) {
  await capture('Rate limited: 429 `RATE_LIMITED` with `Retry-After` (POST /orders is limited per caller)', { method: 'POST', path: '/api/v1/orders', token: op, body: orderBody(CUSTOMER_1, [[16, 1]]), headers: { 'Idempotency-Key': 'ex-<fresh key>' }, send: async () => limited, note: 'Reached by sending small valid POST /orders requests (SKU-1016 x1, each creating a real order) back-to-back as one caller until its token bucket was empty (bucket capacity 20, refill 5/s, `oms.order.rate-limit.*`).' });
} else sections.push('### (not reproduced) 429\n\nSending 80 valid POST /orders requests back-to-back did not exhaust the bucket in this run.\n');
await sleep(100);

const header = `# API Examples (captured from the running stack)

**Status: REAL.** Every request/response below was produced by \`node scripts/capture-api-examples.mjs\` against the local no-Docker stack (\`scripts/run-local.sh\`: embedded Kafka, 4 services, gateway) on ${new Date().toISOString().slice(0, 10)}, through the gateway at \`${BASE}\`. Nothing is hand-written. Bearer tokens are redacted (\`<redacted>\`), the demo password is a placeholder, ids are the values generated in that run (orders are created fresh on every run, so ids differ each time; seed ids are fixed). Long arrays are shortened (marked). All data is synthetic.

Re-generate: start the stack, then \`OMS_DEMO_PASSWORD=<printed value> OMS_JWT_SECRET=<only needed for the expired-token example> node scripts/capture-api-examples.mjs\`.

Base path \`/api/v1\`. Common headers: \`Authorization: Bearer <JWT>\`, \`Content-Type: application/json\`, optional \`X-Correlation-Id\` (echoed back, generated when absent).
Error shape (RFC 7807, \`application/problem+json\`): \`type, title, status, detail, instance, code, correlationId, timestamp, errors[]\`.

| Item | Value |
|---|---|
| customer1 (Acme Industrial Supply) | \`c0000000-0000-0000-0000-000000000001\` |
| customer2 (Globex Logistics) | \`c0000000-0000-0000-0000-000000000002\` |
| SKU-1001 (stock 500) | \`${sku(1)}\` |
| SKU-1019 (stock 3) | \`${sku(19)}\` |
| SKU-1020 (stock 0) | \`${sku(20)}\` |
| Demo users | \`customer1\`, \`customer2\` (CUSTOMER), \`operator1\` (OPERATOR), \`admin1\` (ADMIN) |

---

`;
fs.writeFileSync(OUT, header + sections.join('\n') + '\n');
console.log(`wrote ${n} captured examples to ${path.relative(ROOT, OUT)}`);
void loginRes;

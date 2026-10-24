// Generates tests/api-collection/oms.postman_collection.json, oms.postman_environment.template.json and oms.http
// (VS Code REST Client / JetBrains HTTP format) from ONE request catalog, so the three artefacts cannot drift apart.
//   node scripts/gen-api-collection.mjs
// No secrets: the demo password is a placeholder variable ({{demoPassword}} / @demoPassword read from the environment),
// tokens are obtained by the "Auth" requests at run time and stored in variables.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'tests/api-collection');
fs.mkdirSync(OUT, { recursive: true });

const P = (n) => `a0000000-0000-0000-0000-0000000010${String(n).padStart(2, '0')}`; // product id of SKU-10NN
const C1 = 'c0000000-0000-0000-0000-000000000001';
const addr = { line1: '1 Test Street', city: 'Springfield', postalCode: '12345', country: 'US' };
const order = (items, city = 'Springfield', cust = C1) => ({ customerId: cust, items: items.map(([n, q]) => ({ productId: P(n), quantity: q })), shippingAddress: { ...addr, city } });

// tokenVar: variable holding the bearer token; null = unauthenticated. `save`: collection variable to capture from the JSON response.
const folders = [
  { name: '01 Auth (gateway, profile dev only)', items: [
    { name: 'Login customer1', method: 'POST', path: '/api/v1/auth/token', body: { username: 'customer1', password: '{{demoPassword}}' }, save: { customer1Token: 'accessToken' }, expect: 200 },
    { name: 'Login customer2', method: 'POST', path: '/api/v1/auth/token', body: { username: 'customer2', password: '{{demoPassword}}' }, save: { customer2Token: 'accessToken' }, expect: 200 },
    { name: 'Login operator1', method: 'POST', path: '/api/v1/auth/token', body: { username: 'operator1', password: '{{demoPassword}}' }, save: { operatorToken: 'accessToken' }, expect: 200 },
    { name: 'Login admin1', method: 'POST', path: '/api/v1/auth/token', body: { username: 'admin1', password: '{{demoPassword}}' }, save: { adminToken: 'accessToken' }, expect: 200 },
    { name: 'Login with wrong password -> 401', method: 'POST', path: '/api/v1/auth/token', body: { username: 'customer1', password: 'wrong-password-123' }, expect: 401 },
  ] },
  { name: '02 Catalog and inventory', items: [
    { name: 'List products (customer)', method: 'GET', path: '/api/v1/products?size=20', token: 'customer1Token', expect: 200 },
    { name: 'Get product SKU-1019 (stock 3)', method: 'GET', path: `/api/v1/products/${P(19)}`, token: 'customer1Token', expect: 200 },
    { name: 'Inventory list (operator)', method: 'GET', path: '/api/v1/inventory?size=20', token: 'operatorToken', expect: 200 },
    { name: 'Inventory low stock (operator)', method: 'GET', path: '/api/v1/inventory?lowStock=true', token: 'operatorToken', expect: 200 },
    { name: 'Adjust stock +5 SKU-1001 (operator)', method: 'PUT', path: `/api/v1/inventory/${P(1)}/stock`, token: 'operatorToken', body: { delta: 5, reason: 'collection: cycle count' }, expect: 200 },
    { name: 'Stock movements SKU-1001', method: 'GET', path: `/api/v1/inventory/${P(1)}/movements`, token: 'operatorToken', expect: 200 },
    { name: 'Customer cannot read /inventory -> 403', method: 'GET', path: '/api/v1/inventory', token: 'customer1Token', expect: 403 },
    { name: 'Reservations (operator)', method: 'GET', path: '/api/v1/inventory/reservations?size=10', token: 'operatorToken', expect: 200 },
  ] },
  { name: '03 Orders', items: [
    { name: 'Create order (happy path, SKU-1001 x2)', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', idem: true, body: order([[1, 2]]), save: { orderId: 'id' }, expect: 202 },
    { name: 'Replay same key -> 202 + Idempotent-Replay', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', idem: 'reuse', body: order([[1, 2]]), expect: 202, note: 'Run immediately after the previous request: it reuses the same Idempotency-Key (stored in {{lastIdempotencyKey}}).' },
    { name: 'Same key, different body -> 422', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', idem: 'reuse', body: order([[1, 3]]), expect: 422 },
    { name: 'Missing Idempotency-Key -> 400', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', body: order([[1, 1]]), expect: 400 },
    { name: 'Validation error (quantity 0) -> 400', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', idem: true, body: order([[1, 0]]), expect: 400 },
    { name: 'Insufficient stock SKU-1020 (async REJECTED)', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', idem: true, body: order([[20, 1]]), save: { rejectedOrderId: 'id' }, expect: 202 },
    { name: 'Failed fulfillment (city FAILTOWN)', method: 'POST', path: '/api/v1/orders', token: 'customer1Token', idem: true, body: order([[4, 1]], 'FAILTOWN'), save: { failtownOrderId: 'id' }, expect: 202 },
    { name: 'Get order', method: 'GET', path: '/api/v1/orders/{{orderId}}', token: 'customer1Token', expect: 200 },
    { name: 'Order status history', method: 'GET', path: '/api/v1/orders/{{orderId}}/history', token: 'customer1Token', expect: 200 },
    { name: 'List my orders', method: 'GET', path: '/api/v1/orders?size=10&sort=createdAt,desc', token: 'customer1Token', expect: 200 },
    { name: 'Cross-customer read -> 404', method: 'GET', path: '/api/v1/orders/{{orderId}}', token: 'customer2Token', expect: 404 },
    { name: 'Cancel order', method: 'POST', path: '/api/v1/orders/{{orderId}}/cancel', token: 'customer1Token', body: { reason: 'collection cancel' }, expect: [200, 409], note: '409 INVALID_STATE_TRANSITION when the order already reached a terminal state.' },
    { name: 'No token -> 401', method: 'GET', path: '/api/v1/orders', expect: 401 },
  ] },
  { name: '04 Fulfillment and notifications', items: [
    { name: 'Fulfillment of order (operator)', method: 'GET', path: '/api/v1/fulfillments?orderId={{orderId}}', token: 'operatorToken', expect: 200 },
    { name: 'Customer cannot read fulfillments -> 403', method: 'GET', path: '/api/v1/fulfillments', token: 'customer1Token', expect: 403 },
    { name: 'Notifications of order (customer, own only)', method: 'GET', path: '/api/v1/notifications?orderId={{orderId}}', token: 'customer1Token', expect: 200 },
    { name: 'Notifications (operator, all)', method: 'GET', path: '/api/v1/notifications?size=10', token: 'operatorToken', expect: 200 },
  ] },
  { name: '05 Operations and admin', items: [
    { name: 'Gateway aggregate health', method: 'GET', path: '/api/v1/ops/health', token: 'operatorToken', expect: 200 },
    { name: 'Messaging ops - order', method: 'GET', path: '/api/v1/ops/order/messaging', token: 'operatorToken', expect: 200 },
    { name: 'Messaging ops - inventory', method: 'GET', path: '/api/v1/ops/inventory/messaging', token: 'operatorToken', expect: 200 },
    { name: 'Messaging ops - fulfillment', method: 'GET', path: '/api/v1/ops/fulfillment/messaging', token: 'operatorToken', expect: 200 },
    { name: 'Messaging ops - notification', method: 'GET', path: '/api/v1/ops/notification/messaging', token: 'operatorToken', expect: 200 },
    { name: 'Audit of order', method: 'GET', path: '/api/v1/ops/order/audit?entityType=Order&entityId={{orderId}}', token: 'operatorToken', expect: 200 },
    { name: 'Failed orders', method: 'GET', path: '/api/v1/admin/orders/failed?size=10', token: 'operatorToken', expect: 200 },
    { name: 'Saga of order', method: 'GET', path: '/api/v1/admin/saga/{{orderId}}', token: 'operatorToken', expect: 200 },
    { name: 'Reconciliation (admin only)', method: 'POST', path: '/api/v1/admin/reconciliation/run', token: 'adminToken', expect: 200 },
    { name: 'Reconciliation as operator -> 403', method: 'POST', path: '/api/v1/admin/reconciliation/run', token: 'operatorToken', expect: 403 },
    { name: 'Customers (operator)', method: 'GET', path: '/api/v1/customers?size=10', token: 'operatorToken', expect: 200 },
  ] },
];

const VARS = ['baseUrl', 'demoPassword', 'customer1Token', 'customer2Token', 'operatorToken', 'adminToken', 'orderId', 'rejectedOrderId', 'failtownOrderId', 'lastIdempotencyKey'];
const placeholder = { baseUrl: 'http://localhost:18080', demoPassword: '<set-from-run-local-output>' };

// ------------------------------------------------------------------ Postman
const mkItem = (it) => {
  const headers = [{ key: 'Accept', value: 'application/json' }];
  if (it.body) headers.push({ key: 'Content-Type', value: 'application/json' });
  if (it.idem === true) headers.push({ key: 'Idempotency-Key', value: '{{$guid}}' });
  if (it.idem === 'reuse') headers.push({ key: 'Idempotency-Key', value: '{{lastIdempotencyKey}}' });
  const exp = Array.isArray(it.expect) ? it.expect : [it.expect];
  const test = [`pm.test('status is ${exp.join(' or ')}', function () { pm.expect([${exp.join(',')}]).to.include(pm.response.code); });`];
  for (const [v, field] of Object.entries(it.save ?? {})) test.push(`if (pm.response.code < 300) { pm.collectionVariables.set('${v}', pm.response.json().${field}); }`);
  const events = [{ listen: 'test', script: { type: 'text/javascript', exec: test } }];
  if (it.idem === true) events.unshift({ listen: 'prerequest', script: { type: 'text/javascript', exec: ["const k = 'pm-' + pm.variables.replaceIn('{{$guid}}'); pm.request.headers.upsert({ key: 'Idempotency-Key', value: k }); pm.collectionVariables.set('lastIdempotencyKey', k);"] } });
  return {
    name: it.name,
    event: events,
    request: {
      method: it.method,
      header: headers,
      ...(it.token ? { auth: { type: 'bearer', bearer: [{ key: 'token', value: `{{${it.token}}}`, type: 'string' }] } } : { auth: { type: 'noauth' } }),
      ...(it.body ? { body: { mode: 'raw', raw: JSON.stringify(it.body, null, 2), options: { raw: { language: 'json' } } } } : {}),
      url: { raw: `{{baseUrl}}${it.path}`, host: ['{{baseUrl}}'], path: it.path.split('?')[0].split('/').filter(Boolean), ...(it.path.includes('?') ? { query: it.path.split('?')[1].split('&').map((kv) => { const [key, value] = kv.split('='); return { key, value }; }) } : {}) },
      ...(it.note ? { description: it.note } : {}),
    },
  };
};
const collection = {
  info: { name: 'OMS API (through the gateway)', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json', description: 'Run folders in order. Set demoPassword in the environment (printed by scripts/run-local.sh). No secrets are stored in this file.' },
  variable: VARS.map((key) => ({ key, value: placeholder[key] ?? '' })),
  item: folders.map((f) => ({ name: f.name, item: f.items.map(mkItem) })),
};
fs.writeFileSync(path.join(OUT, 'oms.postman_collection.json'), JSON.stringify(collection, null, 2) + '\n');
fs.writeFileSync(path.join(OUT, 'oms.postman_environment.template.json'), JSON.stringify({
  id: 'oms-local-template', name: 'OMS local (template)', _postman_variable_scope: 'environment',
  values: VARS.map((key) => ({ key, value: placeholder[key] ?? '', type: key.endsWith('Token') || key === 'demoPassword' ? 'secret' : 'default', enabled: true })),
}, null, 2) + '\n');

// ------------------------------------------------------------------ .http (REST Client)
const lines = [
  '### OMS API requests for the VS Code "REST Client" extension (or the JetBrains HTTP client).',
  '### Generated by scripts/gen-api-collection.mjs from the same catalog as the Postman collection. Do not edit by hand.',
  '### Put the demo password (printed by scripts/run-local.sh) in an environment variable named OMS_DEMO_PASSWORD, or',
  '### in a REST Client environment file; never type it into this file. Run the Auth requests first: they store the tokens.',
  '',
  '@baseUrl = http://localhost:18080',
  '@demoPassword = {{$processEnv OMS_DEMO_PASSWORD}}',
  '',
];
let n = 0;
let lastIdemReq = null;
for (const f of folders) {
  lines.push(`########## ${f.name}`, '');
  for (const it of f.items) {
    n++;
    const nm = `r${n}`;
    lines.push(`### ${it.name}${it.note ? ' -- ' + it.note : ''}`);
    lines.push(`# @name ${nm}`);
    lines.push(`${it.method} {{baseUrl}}${it.path.replace(/\{\{(\w+)\}\}/g, '{{$1}}')}`);
    lines.push('Accept: application/json');
    if (it.token) lines.push(`Authorization: Bearer {{${it.token}}}`);
    if (it.idem === true) { lines.push('Idempotency-Key: {{$guid}}'); lastIdemReq = nm; }
    if (it.idem === 'reuse') lines.push(`Idempotency-Key: {{${lastIdemReq}.request.headers.Idempotency-Key}}`);
    if (it.body) { lines.push('Content-Type: application/json', '', JSON.stringify(it.body, null, 2)); }
    lines.push('');
    for (const [v, field] of Object.entries(it.save ?? {})) lines.push(`@${v} = {{${nm}.response.body.${field}}}`, '');
  }
}
fs.writeFileSync(path.join(OUT, 'oms.http'), lines.join('\n') + '\n');
console.log(`wrote ${n} requests: oms.postman_collection.json, oms.postman_environment.template.json, oms.http`);

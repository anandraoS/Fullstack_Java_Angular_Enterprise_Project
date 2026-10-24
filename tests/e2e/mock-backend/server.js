#!/usr/bin/env node
'use strict';
/**
 * Tiny mock of the OMS gateway contract (docs/architecture/00-shared-contract.md), Node stdlib only.
 * Implements auth, RBAC, pagination, idempotency, the order state machine/saga with timers, ops/audit endpoints and
 * serves the built Angular app so Playwright can run the SAME specs without the Java stack.
 *
 *   OMS_DEMO_PASSWORD=<pw> [PORT=4300] [MOCK_STEP_MS=500] [UI_DIR=../../../frontend/oms-ui/dist/oms-ui/browser] node server.js
 *
 * Synthetic data only. The JWT here is signed with a per-run random secret; it is a stand-in, not a security model.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 4300);
const STEP_MS = Number(process.env.MOCK_STEP_MS || 500);
const PASSWORD = process.env.OMS_DEMO_PASSWORD;
const UI_DIR = path.resolve(process.env.UI_DIR || path.join(__dirname, '..', '..', '..', 'frontend', 'oms-ui', 'dist', 'oms-ui', 'browser'));
if (!PASSWORD) {
  console.error('OMS_DEMO_PASSWORD is required (no default password exists).');
  process.exit(1);
}
const SECRET = crypto.randomBytes(32);

const CUSTOMER_1 = 'c0000000-0000-0000-0000-000000000001';
const CUSTOMER_2 = 'c0000000-0000-0000-0000-000000000002';
const CUSTOMER_3 = 'c0000000-0000-0000-0000-000000000003';
const USERS = {
  customer1: { roles: ['CUSTOMER'], customerId: CUSTOMER_1 },
  customer2: { roles: ['CUSTOMER'], customerId: CUSTOMER_2 },
  operator1: { roles: ['OPERATOR'], customerId: null },
  admin1: { roles: ['ADMIN'], customerId: null },
};
const SERVICES = ['order', 'inventory', 'fulfillment', 'notification'];
const TERMINAL = ['FULFILLED', 'REJECTED', 'CANCELLED', 'FAILED'];
const CANCELLABLE = ['CREATED', 'VALIDATED', 'INVENTORY_RESERVED', 'CONFIRMED'];

/* ------------------------------------------------------------------ state */
let S;
function freshState() {
  const names = ['Hex Bolt M8', 'Hex Nut M8', 'Washer 8mm', 'Cordless Drill', 'Safety Glasses', 'Work Gloves', 'Extension Cord 10m', 'LED Panel 40W', 'Cable Ties 100pk', 'Tape Measure 5m',
    'Utility Knife', 'Ratchet Set', 'Torque Wrench', 'Safety Helmet', 'Ear Protection', 'Zip Bags 500pk', 'Pallet Wrap', 'Label Printer Roll', 'Barcode Scanner', 'Discontinued Widget'];
  const cats = ['Fasteners', 'Fasteners', 'Fasteners', 'Tools', 'Safety', 'Safety', 'Electrical', 'Electrical', 'Packaging', 'Tools'];
  const stock = [500, 250, 300, 40, 120, 200, 60, 35, 150, 90, 75, 45, 20, 80, 110, 400, 60, 8, 3, 0];
  const products = names.map((name, i) => {
    const n = String(i + 1).padStart(2, '0');
    return {
      id: `a0000000-0000-0000-0000-0000000010${n}`, sku: `SKU-10${n}`, name, description: `${name} (synthetic)`, category: cats[i % cats.length],
      price: Math.round((2 + i * 3.37) * 100) / 100, currency: 'USD', active: true, onHand: stock[i], reserved: 0, version: 1,
    };
  });
  return {
    products,
    customers: [
      { id: CUSTOMER_1, name: 'Acme Industrial Supply', email: 'procurement@acme.example.test', tier: 'GOLD', createdAt: '2026-01-01T00:00:00Z' },
      { id: CUSTOMER_2, name: 'Globex Logistics', email: 'orders@globex.example.test', tier: 'SILVER', createdAt: '2026-01-01T00:00:00Z' },
      { id: CUSTOMER_3, name: 'Initech Failures', email: 'ops@fail.example.test', tier: 'BRONZE', createdAt: '2026-01-01T00:00:00Z' },
    ],
    orders: [], history: new Map(), sagas: new Map(), reservations: [], fulfillments: [], notifications: [],
    audit: { order: [], inventory: [], fulfillment: [], notification: [] },
    idem: new Map(), counters: { processed: { order: 0, inventory: 0, fulfillment: 0, notification: 0 } },
  };
}
S = freshState();
const now = () => new Date().toISOString();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const uuid = () => crypto.randomUUID();
const product = (id) => S.products.find((p) => p.id === id);
const available = (p) => p.onHand - p.reserved;

function audit(svc, entityType, entityId, action, actor, details) {
  S.audit[svc].unshift({ id: uuid(), entityType, entityId, action, actor: actor || 'system', correlationId: null, details: details || null, occurredAt: now() });
  S.counters.processed[svc]++;
}

/* ------------------------------------------------------------- jwt (HS256) */
const b64 = (o) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
function signJwt(claims) {
  const head = b64({ alg: 'HS256', typ: 'JWT' });
  const body = b64(claims);
  return `${head}.${body}.${crypto.createHmac('sha256', SECRET).update(`${head}.${body}`).digest('base64url')}`;
}
function verifyJwt(token) {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const expected = crypto.createHmac('sha256', SECRET).update(`${parts[0]}.${parts[1]}`).digest();
  const given = Buffer.from(parts[2], 'base64url');
  if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) return null;
  try {
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
    return claims.exp * 1000 > Date.now() ? claims : null;
  } catch {
    return null;
  }
}

/* --------------------------------------------------------------- helpers */
class HttpError extends Error {
  constructor(status, code, detail, errors) { super(detail); this.status = status; this.code = code; this.detail = detail; this.errors = errors; }
}
const TITLES = { 400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 413: 'Payload Too Large', 422: 'Unprocessable Entity', 500: 'Internal Server Error' };
function sendJson(res, status, body, headers = {}) {
  const data = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data), ...headers });
  res.end(data);
}
function sendProblem(res, req, err) {
  const body = {
    type: `https://oms.example/problems/${err.code.toLowerCase().replace(/_/g, '-')}`, title: TITLES[err.status] || 'Error', status: err.status, detail: err.detail,
    instance: req.url.split('?')[0], code: err.code, correlationId: req.correlationId, timestamp: now(), errors: err.errors || [],
  };
  const data = JSON.stringify(body);
  res.writeHead(err.status, { 'Content-Type': 'application/problem+json', 'Content-Length': Buffer.byteLength(data), 'X-Correlation-Id': req.correlationId });
  res.end(data);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 64 * 1024) { reject(new HttpError(413, 'PAYLOAD_TOO_LARGE', 'Body exceeds 64 KB')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString())); } catch { reject(new HttpError(400, 'VALIDATION_FAILED', 'Malformed JSON')); }
    });
    req.on('error', reject);
  });
}
function principal(req, ...roles) {
  const h = req.headers.authorization || '';
  const claims = h.startsWith('Bearer ') ? verifyJwt(h.slice(7)) : null;
  if (!claims) throw new HttpError(401, 'UNAUTHENTICATED', 'Missing or invalid token');
  if (roles.length && !roles.some((r) => claims.roles.includes(r))) throw new HttpError(403, 'FORBIDDEN', 'Insufficient role');
  return claims;
}
const isStaff = (p) => p.roles.includes('OPERATOR') || p.roles.includes('ADMIN');

function paginate(items, q, defaultSort) {
  const size = Math.min(Math.max(parseInt(q.get('size') || '20', 10) || 20, 1), 100);
  const page = Math.max(parseInt(q.get('page') || '0', 10) || 0, 0);
  const [field, dir] = (q.get('sort') || defaultSort || '').split(',');
  let list = [...items];
  if (field) {
    list.sort((a, b) => {
      const x = a[field], y = b[field];
      const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x ?? '').localeCompare(String(y ?? ''));
      return dir === 'desc' ? -c : c;
    });
  }
  return { content: list.slice(page * size, page * size + size), page, size, totalElements: list.length, totalPages: Math.max(1, Math.ceil(list.length / size)) };
}
const money = (n) => Math.round(n * 100) / 100;
const productView = (p, staff) => {
  const v = { id: p.id, sku: p.sku, name: p.name, description: p.description, category: p.category, price: p.price, currency: p.currency, active: p.active, available: available(p), version: p.version };
  if (staff) { v.onHand = p.onHand; v.reserved = p.reserved; }
  return v;
};

/* --------------------------------------------------- order state machine */
function transition(order, to, reason, actor) {
  const from = order.status;
  order.status = to; order.statusReason = reason || null; order.updatedAt = now(); order.version++;
  S.history.get(order.id).push({ fromStatus: from, toStatus: to, reason: reason || null, actor: actor || 'system', occurredAt: now() });
  const saga = S.sagas.get(order.id);
  saga.state = TERMINAL.includes(to) ? to : 'RUNNING'; saga.updatedAt = now();
  audit('order', 'Order', order.id, `ORDER_${to}`, actor, reason);
  const notify = { CONFIRMED: 'Order confirmed', FULFILLED: 'Order shipped', CANCELLED: 'Order cancelled', REJECTED: 'Order rejected', FAILED: 'Order failed' }[to];
  if (notify) {
    const cust = S.customers.find((c) => c.id === order.customerId);
    S.notifications.unshift({ id: uuid(), customerId: order.customerId, orderId: order.id, channel: 'EMAIL', template: `order-${to.toLowerCase()}`, subject: `${notify} (${order.id.slice(0, 8)})`,
      status: cust && cust.email.endsWith('fail.example.test') ? 'FAILED' : 'SENT', attempts: 1, lastError: cust && cust.email.endsWith('fail.example.test') ? 'simulated provider failure' : null,
      correlationId: order.correlationId, sourceEventId: uuid(), createdAt: now(), sentAt: now() });
    audit('notification', 'Notification', order.id, 'NOTIFICATION_RECORDED', 'system', notify);
  }
}
function sagaStep(order, step, status, detail) {
  const saga = S.sagas.get(order.id);
  saga.currentStep = step; saga.updatedAt = now();
  saga.steps.push({ step, status, attempt: saga.attempts, detail: detail || null, at: now() });
}
function release(order) {
  const r = S.reservations.find((x) => x.orderId === order.id && x.status === 'RESERVED' || x.orderId === order.id && x.status === 'COMMITTED');
  if (!r) return;
  for (const it of r.items) { const p = product(it.productId); p.reserved = Math.max(0, p.reserved - it.quantity); p.version++; }
  r.status = 'RELEASED'; r.releaseReason = 'Order ' + order.status; r.updatedAt = now(); r.expiresAt = null;
  audit('inventory', 'Reservation', r.id, 'RESERVATION_RELEASED', 'system', r.releaseReason);
}
async function compensate(order, finalStatus, reason) {
  await sleep(STEP_MS);
  sagaStep(order, 'RELEASE_INVENTORY', 'DONE');
  release(order);
  transition(order, finalStatus, reason);
}
async function runSaga(order) {
  const alive = (expected) => order.status === expected;
  await sleep(STEP_MS);
  if (!alive('CREATED')) return;
  sagaStep(order, 'VALIDATE', 'DONE');
  const bad = order.items.find((i) => !product(i.productId) || !product(i.productId).active);
  if (bad) return transition(order, 'REJECTED', 'Invalid or inactive product');
  transition(order, 'VALIDATED');
  await sleep(STEP_MS);
  if (!alive('VALIDATED')) return;
  const short = order.items.filter((i) => available(product(i.productId)) < i.quantity);
  if (short.length) {
    sagaStep(order, 'RESERVE_INVENTORY', 'FAILED', 'Insufficient stock');
    audit('inventory', 'Reservation', order.id, 'RESERVATION_FAILED', 'system', short.map((i) => i.sku).join(','));
    return transition(order, 'REJECTED', `Insufficient stock for ${short.map((i) => i.sku).join(', ')}`);
  }
  const reservation = { id: uuid(), orderId: order.id, status: 'RESERVED', items: order.items.map((i) => ({ productId: i.productId, sku: i.sku, quantity: i.quantity })),
    expiresAt: new Date(Date.now() + 900_000).toISOString(), createdAt: now(), updatedAt: now(), releaseReason: null };
  for (const i of order.items) { const p = product(i.productId); p.reserved += i.quantity; p.version++; }
  S.reservations.unshift(reservation);
  audit('inventory', 'Reservation', reservation.id, 'RESERVATION_CREATED', 'system', order.id);
  sagaStep(order, 'RESERVE_INVENTORY', 'DONE');
  transition(order, 'INVENTORY_RESERVED');
  await sleep(STEP_MS);
  if (!alive('INVENTORY_RESERVED')) return;
  reservation.status = 'COMMITTED'; reservation.expiresAt = null;
  sagaStep(order, 'CONFIRM_ORDER', 'DONE');
  transition(order, 'CONFIRMED');
  const f = { id: uuid(), orderId: order.id, status: 'PENDING', trackingNumber: null, attempts: 0, failureReason: null, events: [{ status: 'PENDING', at: now() }], createdAt: now(), updatedAt: now() };
  S.fulfillments.unshift(f);
  sagaStep(order, 'FULFILL', 'STARTED');
  const setF = (status) => { f.status = status; f.updatedAt = now(); f.events.push({ status, at: now() }); audit('fulfillment', 'Fulfillment', f.id, `FULFILLMENT_${status}`, 'system', order.id); };
  if ((order.shippingAddress.city || '').toUpperCase() === 'FAILTOWN') {
    for (let a = 1; a <= 3; a++) {
      await sleep(STEP_MS / 2);
      if (!alive('CONFIRMED')) return;
      f.attempts = a;
    }
    f.failureReason = 'Carrier could not deliver to FAILTOWN';
    setF('FAILED');
    sagaStep(order, 'FULFILL', 'FAILED', f.failureReason);
    transition(order, 'COMPENSATION_PENDING', 'Fulfillment failed: ' + f.failureReason);
    return compensate(order, 'FAILED', 'Fulfillment failed: ' + f.failureReason);
  }
  for (const st of ['PICKING', 'PACKED', 'SHIPPED', 'COMPLETED']) {
    await sleep(STEP_MS);
    if (!alive('CONFIRMED')) { setF('FAILED'); return; }
    if (st === 'COMPLETED') f.trackingNumber = 'TRK' + crypto.randomBytes(4).toString('hex').toUpperCase();
    setF(st);
  }
  for (const i of order.items) { const p = product(i.productId); p.onHand -= i.quantity; p.reserved -= i.quantity; p.version++; }
  sagaStep(order, 'FULFILL', 'DONE');
  transition(order, 'FULFILLED');
}

/* --------------------------------------------------------------- routes */
function orderView(o) { return { ...o }; }
function ownOrder(p, id) {
  const o = S.orders.find((x) => x.id === id);
  if (!o || (!isStaff(p) && o.customerId !== p.customerId)) throw new HttpError(404, 'NOT_FOUND', 'Order not found');
  return o;
}
function validationError(errors) { return new HttpError(400, 'VALIDATION_FAILED', 'Request validation failed', errors); }

async function route(req, res, url) {
  const m = req.method;
  const p = url.pathname;
  const q = url.searchParams;
  let match;

  if (p === '/api/v1/auth/token' && m === 'POST') {
    const b = await readBody(req);
    const u = USERS[b.username];
    const ok = u && typeof b.password === 'string' && b.password.length === PASSWORD.length && crypto.timingSafeEqual(Buffer.from(b.password), Buffer.from(PASSWORD));
    if (!ok) throw new HttpError(401, 'UNAUTHENTICATED', 'Invalid credentials');
    const iat = Math.floor(Date.now() / 1000);
    const accessToken = signJwt({ sub: b.username, roles: u.roles, customerId: u.customerId, iss: 'oms-dev-issuer', aud: 'oms-api', iat, exp: iat + 3600, jti: uuid() });
    return sendJson(res, 200, { accessToken, tokenType: 'Bearer', expiresIn: 3600, username: b.username, roles: u.roles, customerId: u.customerId });
  }

  /* ---- customers */
  if (p === '/api/v1/customers' && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    const term = (q.get('q') || '').toLowerCase();
    return sendJson(res, 200, paginate(S.customers.filter((c) => !term || c.name.toLowerCase().includes(term) || c.email.includes(term)), q, 'name,asc'));
  }
  if ((match = p.match(/^\/api\/v1\/customers\/([^/]+)$/)) && m === 'GET') {
    const pr = principal(req);
    const c = S.customers.find((x) => x.id === match[1]);
    if (!c || (!isStaff(pr) && pr.customerId !== c.id)) throw new HttpError(404, 'NOT_FOUND', 'Customer not found');
    return sendJson(res, 200, c);
  }

  /* ---- products / inventory */
  if (p === '/api/v1/products' && m === 'GET') {
    const pr = principal(req);
    const term = (q.get('q') || '').toLowerCase();
    const cat = (q.get('category') || '').toLowerCase();
    let list = S.products.filter((x) => x.active && (!term || x.name.toLowerCase().includes(term) || x.sku.toLowerCase().includes(term)) && (!cat || x.category.toLowerCase().includes(cat)));
    if (q.get('inStock') === 'true') list = list.filter((x) => available(x) > 0);
    const views = list.map((x) => productView(x, isStaff(pr)));
    return sendJson(res, 200, paginate(views, q, 'sku,asc'));
  }
  if (p === '/api/v1/inventory' && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    const sku = (q.get('sku') || '').toLowerCase();
    let list = S.products.filter((x) => !sku || x.sku.toLowerCase().includes(sku));
    if (q.get('lowStock') === 'true') list = list.filter((x) => available(x) < 10);
    return sendJson(res, 200, paginate(list.map((x) => productView(x, true)), q, 'sku,asc'));
  }
  if (p === '/api/v1/inventory/reservations' && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    let list = S.reservations.filter((r) => (!q.get('orderId') || r.orderId.startsWith(q.get('orderId'))) && (!q.get('status') || r.status === q.get('status')));
    return sendJson(res, 200, paginate(list, q, 'createdAt,desc'));
  }
  if ((match = p.match(/^\/api\/v1\/inventory\/([^/]+)\/stock$/)) && m === 'PUT') {
    const pr = principal(req, 'OPERATOR', 'ADMIN');
    const b = await readBody(req);
    const prod = product(match[1]);
    if (!prod) throw new HttpError(404, 'NOT_FOUND', 'Product not found');
    const errors = [];
    if (!Number.isInteger(b.delta) || b.delta === 0) errors.push({ field: 'delta', message: 'must be a non-zero integer' });
    if (typeof b.reason !== 'string' || b.reason.trim().length < 3) errors.push({ field: 'reason', message: 'is required' });
    if (errors.length) throw validationError(errors);
    if (prod.onHand + b.delta < prod.reserved) throw new HttpError(409, 'CONFLICT', 'Resulting stock would be below reserved quantity');
    prod.onHand += b.delta; prod.version++;
    audit('inventory', 'Product', prod.id, 'STOCK_ADJUSTED', pr.sub, `${b.delta} (${b.reason})`);
    return sendJson(res, 200, productView(prod, true));
  }

  /* ---- orders */
  if (p === '/api/v1/orders' && m === 'POST') {
    const pr = principal(req, 'CUSTOMER', 'OPERATOR', 'ADMIN');
    const key = req.headers['idempotency-key'];
    if (!key) throw new HttpError(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key header is required');
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(key)) throw validationError([{ field: 'Idempotency-Key', message: 'must be 8-64 chars of [A-Za-z0-9_-]' }]);
    const b = await readBody(req);
    const fp = crypto.createHash('sha256').update(JSON.stringify([b.customerId, [...(b.items || [])].sort((x, y) => String(x.productId).localeCompare(String(y.productId))), b.shippingAddress])).digest('hex');
    const prior = S.idem.get(`${pr.sub}:${key}`);
    if (prior) {
      if (prior.fp !== fp) throw new HttpError(422, 'IDEMPOTENCY_KEY_REUSED', 'Idempotency-Key was used with a different request body');
      const o = S.orders.find((x) => x.id === prior.orderId);
      return sendJson(res, 202, orderView(o), { Location: `/api/v1/orders/${o.id}`, 'Idempotent-Replay': 'true' });
    }
    const errors = [];
    if (!b.customerId) errors.push({ field: 'customerId', message: 'is required' });
    if (!Array.isArray(b.items) || b.items.length < 1 || b.items.length > 50) errors.push({ field: 'items', message: 'must contain 1-50 lines' });
    const seen = new Set();
    (b.items || []).forEach((it, i) => {
      if (!Number.isInteger(it.quantity) || it.quantity < 1 || it.quantity > 1000) errors.push({ field: `items[${i}].quantity`, message: 'must be between 1 and 1000' });
      if (seen.has(it.productId)) errors.push({ field: `items[${i}].productId`, message: 'duplicate product' });
      seen.add(it.productId);
    });
    const a = b.shippingAddress || {};
    for (const f of ['line1', 'city', 'postalCode', 'country']) if (!a[f] || typeof a[f] !== 'string') errors.push({ field: `shippingAddress.${f}`, message: 'is required' });
    if (errors.length) throw validationError(errors);
    if (!isStaff(pr) && b.customerId !== pr.customerId) throw new HttpError(403, 'FORBIDDEN', 'Customers may only order for themselves');
    if (!S.customers.some((c) => c.id === b.customerId)) throw new HttpError(404, 'NOT_FOUND', 'Customer not found');
    const items = b.items.map((it) => {
      const prod = product(it.productId);
      if (!prod) throw new HttpError(422, 'VALIDATION_FAILED', 'Unknown product', [{ field: 'items', message: `unknown product ${it.productId}` }]);
      return { productId: prod.id, sku: prod.sku, name: prod.name, quantity: it.quantity, unitPrice: prod.price, lineTotal: money(prod.price * it.quantity) };
    });
    const order = {
      id: uuid(), customerId: b.customerId, status: 'CREATED', statusReason: null, totalAmount: money(items.reduce((s, i) => s + i.lineTotal, 0)), currency: 'USD', items,
      shippingAddress: { line1: a.line1, city: a.city, postalCode: a.postalCode, country: a.country }, correlationId: req.correlationId, createdAt: now(), updatedAt: now(), version: 0,
    };
    S.orders.unshift(order);
    S.history.set(order.id, [{ fromStatus: null, toStatus: 'CREATED', reason: null, actor: pr.sub, occurredAt: now() }]);
    S.sagas.set(order.id, { orderId: order.id, sagaId: uuid(), state: 'RUNNING', currentStep: 'VALIDATE', attempts: 1, startedAt: now(), updatedAt: now(), lastError: null, steps: [] });
    S.idem.set(`${pr.sub}:${key}`, { fp, orderId: order.id });
    audit('order', 'Order', order.id, 'ORDER_CREATED', pr.sub, null);
    runSaga(order).catch((e) => console.error('saga error', e));
    return sendJson(res, 202, orderView(order), { Location: `/api/v1/orders/${order.id}` });
  }
  if (p === '/api/v1/orders' && m === 'GET') {
    const pr = principal(req);
    const term = (q.get('q') || '').toLowerCase();
    let list = S.orders.filter((o) => {
      if (!isStaff(pr) && o.customerId !== pr.customerId) return false;
      if (q.get('customerId') && isStaff(pr) && o.customerId !== q.get('customerId')) return false;
      if (q.get('status') && o.status !== q.get('status')) return false;
      if (q.get('from') && o.createdAt < q.get('from')) return false;
      if (q.get('to') && o.createdAt > q.get('to')) return false;
      if (term && !(o.id.includes(term) || o.items.some((i) => i.sku.toLowerCase().includes(term) || i.name.toLowerCase().includes(term)))) return false;
      return true;
    });
    return sendJson(res, 200, paginate(list.map(orderView), q, 'createdAt,desc'));
  }
  if ((match = p.match(/^\/api\/v1\/orders\/([^/]+)$/)) && m === 'GET') return sendJson(res, 200, orderView(ownOrder(principal(req), match[1])));
  if ((match = p.match(/^\/api\/v1\/orders\/([^/]+)\/history$/)) && m === 'GET') {
    const o = ownOrder(principal(req), match[1]);
    return sendJson(res, 200, S.history.get(o.id));
  }
  if ((match = p.match(/^\/api\/v1\/orders\/([^/]+)\/cancel$/)) && m === 'POST') {
    const pr = principal(req);
    const o = ownOrder(pr, match[1]);
    const b = await readBody(req);
    if (typeof b.reason !== 'string' || !b.reason.trim()) throw validationError([{ field: 'reason', message: 'is required' }]);
    if (!CANCELLABLE.includes(o.status)) throw new HttpError(409, 'INVALID_STATE_TRANSITION', `Cannot cancel an order in status ${o.status}`);
    const reason = `Cancelled: ${b.reason.trim()}`;
    if (o.status === 'CREATED' || o.status === 'VALIDATED') {
      transition(o, 'CANCELLED', reason, pr.sub);
    } else {
      transition(o, 'COMPENSATION_PENDING', reason, pr.sub);
      compensate(o, 'CANCELLED', reason).catch((e) => console.error(e));
    }
    return sendJson(res, 200, orderView(o));
  }
  if (p === '/api/v1/admin/orders/failed' && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    return sendJson(res, 200, paginate(S.orders.filter((o) => ['REJECTED', 'FAILED', 'COMPENSATION_PENDING'].includes(o.status)).map(orderView), q, 'updatedAt,desc'));
  }
  if ((match = p.match(/^\/api\/v1\/admin\/orders\/([^/]+)\/retry$/)) && m === 'POST') {
    const pr = principal(req, 'OPERATOR', 'ADMIN');
    const o = S.orders.find((x) => x.id === match[1]);
    if (!o) throw new HttpError(404, 'NOT_FOUND', 'Order not found');
    const saga = S.sagas.get(o.id);
    saga.attempts++; saga.lastError = null;
    sagaStep(o, saga.currentStep, 'RETRY_REQUESTED', `retry by ${pr.sub}`);
    audit('order', 'Order', o.id, 'ORDER_RETRY_REQUESTED', pr.sub, null);
    return sendJson(res, 200, { orderId: o.id, accepted: true });
  }
  if ((match = p.match(/^\/api\/v1\/admin\/saga\/([^/]+)$/)) && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    const s = S.sagas.get(match[1]);
    if (!s) throw new HttpError(404, 'NOT_FOUND', 'Saga not found');
    return sendJson(res, 200, s);
  }
  if (p === '/api/v1/admin/reconciliation/run' && m === 'POST') {
    const pr = principal(req, 'ADMIN');
    audit('order', 'Reconciliation', 'run', 'RECONCILIATION_RUN', pr.sub, null);
    return sendJson(res, 200, { examined: S.orders.length, repaired: 0, details: S.orders.length ? ['No inconsistencies found'] : [] });
  }

  /* ---- fulfillment / notification */
  if (p === '/api/v1/fulfillments' && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    return sendJson(res, 200, paginate(S.fulfillments.filter((f) => (!q.get('orderId') || f.orderId.startsWith(q.get('orderId'))) && (!q.get('status') || f.status === q.get('status'))), q, 'createdAt,desc'));
  }
  if ((match = p.match(/^\/api\/v1\/admin\/fulfillments\/([^/]+)\/retry$/)) && m === 'POST') {
    const pr = principal(req, 'OPERATOR', 'ADMIN');
    const f = S.fulfillments.find((x) => x.id === match[1]);
    if (!f) throw new HttpError(404, 'NOT_FOUND', 'Fulfillment not found');
    f.attempts++; f.updatedAt = now(); f.events.push({ status: 'RETRY', at: now() });
    audit('fulfillment', 'Fulfillment', f.id, 'FULFILLMENT_RETRY_REQUESTED', pr.sub, null);
    return sendJson(res, 200, f);
  }
  if (p === '/api/v1/notifications' && m === 'GET') {
    const pr = principal(req);
    let list = S.notifications.filter((n) => (isStaff(pr) || n.customerId === pr.customerId) && (!q.get('status') || n.status === q.get('status')) && (!q.get('orderId') || (n.orderId || '').startsWith(q.get('orderId'))));
    return sendJson(res, 200, paginate(list, q, 'createdAt,desc'));
  }
  if ((match = p.match(/^\/api\/v1\/admin\/notifications\/([^/]+)\/retry$/)) && m === 'POST') {
    const pr = principal(req, 'OPERATOR', 'ADMIN');
    const n = S.notifications.find((x) => x.id === match[1]);
    if (!n) throw new HttpError(404, 'NOT_FOUND', 'Notification not found');
    n.attempts++; n.status = 'SENT'; n.lastError = null; n.sentAt = now();
    audit('notification', 'Notification', n.id, 'NOTIFICATION_RETRIED', pr.sub, null);
    return sendJson(res, 200, n);
  }

  /* ---- ops */
  if (p === '/api/v1/ops/health' && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    return sendJson(res, 200, { status: 'UP', services: Object.fromEntries(SERVICES.map((s) => [s, { status: 'UP' }])) });
  }
  if ((match = p.match(/^\/api\/v1\/ops\/(order|inventory|fulfillment|notification)\/(messaging|audit)$/)) && m === 'GET') {
    principal(req, 'OPERATOR', 'ADMIN');
    const svc = match[1];
    if (match[2] === 'messaging') {
      const topics = { order: ['oms.inventory.events.v1', 'oms.fulfillment.events.v1'], inventory: ['oms.inventory.commands.v1', 'oms.order.events.v1'], fulfillment: ['oms.fulfillment.commands.v1'], notification: ['oms.order.events.v1'] }[svc];
      return sendJson(res, 200, { service: `${svc}-service`, consumerGroup: `${svc}-service`, topics: topics.map((t) => ({ topic: t, partitions: 3, consumerLag: 0, committedOffsets: { 0: 1, 1: 1, 2: 1 } })),
        processedEvents: S.counters.processed[svc], duplicatesIgnored: 0, retries: 0, deadLettered: 0, outboxPending: 0, lastProcessedAt: S.audit[svc][0] ? S.audit[svc][0].occurredAt : null, dltTopics: topics.map((t) => `${t}.DLT`), lagAvailable: true });
    }
    let list = S.audit[svc].filter((a) => (!q.get('entityType') || a.entityType === q.get('entityType')) && (!q.get('entityId') || a.entityId === q.get('entityId')));
    return sendJson(res, 200, paginate(list, q, 'occurredAt,desc'));
  }

  /* ---- mock control */
  if (p === '/__mock/reset' && m === 'POST') { S = freshState(); return sendJson(res, 200, { ok: true }); }
  if (p === '/__mock/health') return sendJson(res, 200, { ok: true, stepMs: STEP_MS });

  throw new HttpError(404, 'NOT_FOUND', 'No such endpoint');
}

/* ------------------------------------------------------------ static UI */
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.png': 'image/png', '.woff2': 'font/woff2', '.map': 'application/json', '.txt': 'text/plain' };
function serveStatic(req, res, url) {
  let rel = decodeURIComponent(url.pathname);
  let file = path.normalize(path.join(UI_DIR, rel));
  if (!file.startsWith(UI_DIR)) { res.writeHead(403); return res.end(); }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(UI_DIR, 'index.html'); // SPA fallback
  if (!fs.existsSync(file)) { res.writeHead(404, { 'Content-Type': 'text/plain' }); return res.end(`UI not built: ${UI_DIR}`); }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  req.correlationId = req.headers['x-correlation-id'] || uuid();
  if (!url.pathname.startsWith('/api/') && !url.pathname.startsWith('/__mock/')) return serveStatic(req, res, url);
  res.setHeader('X-Correlation-Id', req.correlationId);
  try {
    await route(req, res, url);
  } catch (e) {
    if (e instanceof HttpError) return sendProblem(res, req, e);
    console.error(e);
    sendProblem(res, req, new HttpError(500, 'INTERNAL_ERROR', 'Unexpected error'));
  }
});
server.listen(PORT, () => console.log(`mock OMS backend + UI on http://localhost:${PORT} (step ${STEP_MS}ms, ui ${UI_DIR})`));
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));

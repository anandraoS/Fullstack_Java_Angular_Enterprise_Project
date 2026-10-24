// Concurrent oversell scenario: many customers race for SKU-1019 whose seed stock is 3.
// Correctness assertion (NOT a latency test): at most 3 units may end up reserved/shipped overall, never more.
// NOT EXECUTED during authoring (k6 not installed). Requires a freshly seeded environment (SKU-1019 available == 3),
// otherwise setup() aborts rather than reporting a misleading result (override with -e EXPECTED_STOCK=<n>).
//
//   k6 run -e OMS_DEMO_PASSWORD=... [-e CONTENDERS=40] performance/oversell.js
//
// How the assertion works (all through public APIs, as the operator user):
//   committed = (onHand_before - onHand_after) + (reserved_after - reserved_before)   // units shipped or held
//   ok iff committed <= available_before AND reserved_after <= onHand_after AND confirmed-order count <= available_before
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, jsonHeaders, idempotencyKey, TERMINAL, expectedStatuses } from './lib/common.js';

const SKU = __ENV.SKU || 'SKU-1019';
const EXPECTED_STOCK = Number(__ENV.EXPECTED_STOCK || 3);
const CONTENDERS = Number(__ENV.CONTENDERS || 40);

const ordersAccepted = new Counter('oversell_orders_accepted_stock'); // orders that obtained stock (reserved/confirmed/fulfilled)
const ordersRejected = new Counter('oversell_orders_rejected_stock');
const violations = new Counter('oversell_violations');

export const options = {
  scenarios: {
    race: { executor: 'per-vu-iterations', vus: CONTENDERS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    oversell_violations: ['count==0'],
    oversell_orders_accepted_stock: [`count<=${EXPECTED_STOCK}`],
    checks: ['rate==1'],
  },
};

http.setResponseCallback(expectedStatuses);

function inventoryOf(token, productId) {
  const res = http.get(`${BASE_URL}/api/v1/inventory/${productId}`, { headers: jsonHeaders(token), tags: { name: 'inventory' } });
  check(res, { 'inventory 200': (r) => r.status === 200 });
  return res.json();
}

export function setup() {
  const op = login('operator1');
  const list = http.get(`${BASE_URL}/api/v1/products?q=${SKU}&size=5`, { headers: jsonHeaders(op.token) });
  const product = (list.json('content') || []).find((p) => p.sku === SKU);
  if (!product) exec.test.abort(`${SKU} not found`);
  const before = inventoryOf(op.token, product.id);
  const available = before.onHand - before.reserved;
  if (available !== EXPECTED_STOCK) {
    exec.test.abort(`${SKU} available is ${available}, expected ${EXPECTED_STOCK}: reseed the environment or pass -e EXPECTED_STOCK`);
  }
  return { productId: product.id, before, available };
}

export default function (data) {
  const user = login(`customer${(__VU % 2) + 1}`);
  const body = {
    customerId: user.customerId,
    items: [{ productId: data.productId, quantity: 1 }],
    shippingAddress: { line1: '9 Race Condition Rd', city: 'Springfield', postalCode: '12345', country: 'US' },
  };
  // all VUs start together; this barrier-less version relies on per-vu-iterations starting simultaneously
  const res = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify(body), {
    headers: jsonHeaders(user.token, { 'Idempotency-Key': idempotencyKey('race') }),
    tags: { name: 'create_order' },
  });
  if (!check(res, { 'order accepted (202) or rate limited (429)': (r) => r.status === 202 || r.status === 429 })) return;
  if (res.status === 429) return; // not part of the race; the assertion below uses stock arithmetic, not this count alone
  const id = res.json('id');

  let status = 'CREATED';
  for (let i = 0; i < 60 && TERMINAL.indexOf(status) < 0 && status !== 'CONFIRMED'; i++) {
    sleep(0.5);
    const r = http.get(`${BASE_URL}/api/v1/orders/${id}`, { headers: jsonHeaders(user.token), tags: { name: 'get_order' } });
    if (r.status === 200) status = r.json('status');
  }
  if (status === 'REJECTED') ordersRejected.add(1);
  else if (status === 'CONFIRMED' || status === 'FULFILLED' || status === 'INVENTORY_RESERVED') ordersAccepted.add(1);
}

export function teardown(data) {
  sleep(10); // let in-flight sagas/fulfillment settle before reading final stock
  const op = login('operator1');
  const after = inventoryOf(op.token, data.productId);
  const committed = (data.before.onHand - after.onHand) + (after.reserved - data.before.reserved);
  const ok = committed <= data.available && after.reserved <= after.onHand && after.onHand - after.reserved >= 0;
  console.log(`SKU ${SKU}: available_before=${data.available} committed=${committed} onHand_after=${after.onHand} reserved_after=${after.reserved}`);
  check(null, { 'no oversell: committed units <= initial available stock': () => ok });
  if (!ok) violations.add(1);
}

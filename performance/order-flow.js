// Main user journey: login -> browse products -> create order (unique Idempotency-Key) -> poll status until terminal.
// NOT EXECUTED during authoring (k6 not installed); see performance/README.md.
//
//   k6 run -e PROFILE=smoke  -e OMS_DEMO_PASSWORD=... performance/order-flow.js
//   PROFILE: smoke (default) | load | soak | spike | stress
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, login, jsonHeaders, idempotencyKey, TERMINAL, expectedStatuses } from './lib/common.js';

const orderCompletion = new Trend('oms_order_completion_ms', true);
const ordersCreated = new Counter('oms_orders_created');
const ordersFulfilled = new Counter('oms_orders_fulfilled');
const ordersRejected = new Counter('oms_orders_rejected');
const rateLimited = new Counter('oms_rate_limited_429');

const PROFILES = {
  smoke: { vus: 1, duration: '30s' },
  load: {
    executor: 'ramping-vus', startVUs: 0,
    stages: [{ duration: '2m', target: 20 }, { duration: '10m', target: 20 }, { duration: '1m', target: 0 }],
  },
  soak: {
    executor: 'constant-vus', vus: 15, duration: __ENV.SOAK_DURATION || '2h', // looks for leaks: heap, pool, lag, outbox
  },
  spike: {
    executor: 'ramping-vus', startVUs: 2,
    stages: [{ duration: '1m', target: 2 }, { duration: '20s', target: 80 }, { duration: '2m', target: 80 }, { duration: '20s', target: 2 }, { duration: '2m', target: 2 }],
    gracefulRampDown: '30s',
  },
  stress: {
    executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 400,
    stages: [{ duration: '2m', target: 20 }, { duration: '3m', target: 60 }, { duration: '3m', target: 120 }, { duration: '1m', target: 0 }],
  },
};
const profile = PROFILES[__ENV.PROFILE || 'smoke'];

export const options = {
  scenarios: { journey: Object.assign({ executor: 'constant-vus', exec: 'journey' }, profile) },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:list_products}': ['p(95)<400'],
    'http_req_duration{name:create_order}': ['p(95)<800', 'p(99)<1500'],
    'http_req_duration{name:get_order}': ['p(95)<400'],
    oms_order_completion_ms: ['p(95)<20000'], // CREATED -> terminal as seen by the client (includes simulated fulfillment delay)
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(expectedStatuses);

export function setup() {
  // fail fast if the system is not reachable / password wrong
  login('customer1');
}

export function journey() {
  const session = login(`customer${(__VU % 2) + 1}`);
  const headers = jsonHeaders(session.token);

  let product;
  group('browse', () => {
    const res = http.get(`${BASE_URL}/api/v1/products?size=20&inStock=true`, { headers, tags: { name: 'list_products' } });
    check(res, { 'products 200': (r) => r.status === 200 });
    const items = (res.status === 200 && res.json('content')) || [];
    product = items[Math.floor(Math.random() * items.length)];
    if (product) {
      const one = http.get(`${BASE_URL}/api/v1/products/${product.id}`, { headers, tags: { name: 'get_product' } });
      check(one, { 'product 200': (r) => r.status === 200 });
    }
  });
  if (!product) { sleep(1); return; }

  let orderId;
  const started = Date.now();
  group('order', () => {
    const body = {
      customerId: session.customerId,
      items: [{ productId: product.id, quantity: 1 }],
      shippingAddress: { line1: '1 Load Test Way', city: 'Springfield', postalCode: '12345', country: 'US' },
    };
    const res = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify(body), {
      headers: jsonHeaders(session.token, { 'Idempotency-Key': idempotencyKey('flow') }),
      tags: { name: 'create_order' },
    });
    if (res.status === 429) { rateLimited.add(1); return; }
    const ok = check(res, { 'create order 202': (r) => r.status === 202 });
    if (ok) { orderId = res.json('id'); ordersCreated.add(1); }
  });
  if (!orderId) { sleep(1); return; }

  group('poll', () => {
    let status = 'CREATED';
    for (let i = 0; i < 40 && TERMINAL.indexOf(status) < 0; i++) {
      sleep(0.5);
      const res = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, { headers, tags: { name: 'get_order' } });
      if (res.status === 200) status = res.json('status');
    }
    check(null, { 'order reached a terminal state': () => TERMINAL.indexOf(status) >= 0 });
    if (status === 'FULFILLED') { ordersFulfilled.add(1); orderCompletion.add(Date.now() - started); }
    if (status === 'REJECTED') ordersRejected.add(1);
  });
  sleep(1 + Math.random());
}

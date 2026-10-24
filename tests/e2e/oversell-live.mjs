// LIVE concurrency demonstration through the gateway: N parallel orders (1 unit each) for SKU-1019 (seed stock 3).
// Verifies via the inventory API that  shipped + reserved  never exceeds the starting available stock, that exactly that many
// orders succeed, and that every other order is REJECTED. The numbers printed are measured, not asserted constants.
//   OMS_DEMO_PASSWORD=... node tests/e2e/oversell-live.mjs [orders=30]      (env OMS_BASE_URL, default http://localhost:18080)
// Callers are spread over four identities because order creation is rate limited per JWT subject (capacity 20, see
// order-service application.yml): customer1, customer2 and operator1/admin1 ordering on behalf of a customer.
import { call, login, orderBody, placeOrder, sku, sleep, CUSTOMER_1, CUSTOMER_2, makeChecker, TERMINAL } from '../../scripts/lib/oms-client.mjs';

const N = Number(process.argv[2] ?? 30);
const SKU_N = 19;
const ck = makeChecker();

const [c1, c2, op, ad] = await Promise.all(['customer1', 'customer2', 'operator1', 'admin1'].map((u) => login(u)));
const callers = [
  { name: 'customer1', token: c1.token, customerId: CUSTOMER_1 },
  { name: 'customer2', token: c2.token, customerId: CUSTOMER_2 },
  { name: 'operator1', token: op.token, customerId: CUSTOMER_2 },
  { name: 'admin1', token: ad.token, customerId: CUSTOMER_1 },
];
const staff = op.token;
const stock = async () => (await call('GET', `/api/v1/inventory/${sku(SKU_N)}`, { token: staff })).json;

let start = await stock();
if (start.onHand - start.reserved < 3 && !process.env.OVERSELL_NO_RESTOCK) { // a previous run sold the 3 seed units: restock through the real API (operator, audited)
  const delta = 3 - (start.onHand - start.reserved);
  const r = await call('PUT', `/api/v1/inventory/${sku(SKU_N)}/stock`, { token: staff, body: { delta, reason: 'oversell-live demo restock to 3 units' } });
  console.log(`restocked SKU-10${SKU_N} by ${delta} via PUT /inventory/{id}/stock -> HTTP ${r.status}`);
  start = await stock();
}
const cap = start.onHand - start.reserved;
console.log(`SKU-10${SKU_N}: onHand=${start.onHand} reserved=${start.reserved} available=${cap} at start; firing ${N} parallel orders (qty 1)`);

// sampler: records the worst observed shipped+reserved while the burst is running
let maxCommitted = start.reserved, minAvailable = cap, samples = 0, sampling = true;
const sampler = (async () => {
  while (sampling) {
    const s = await stock();
    if (s?.onHand !== undefined) {
      const committed = (start.onHand - s.onHand) + s.reserved; // shipped so far + currently reserved
      maxCommitted = Math.max(maxCommitted, committed);
      minAvailable = Math.min(minAvailable, s.onHand - s.reserved);
      samples++;
    }
    await sleep(75);
  }
})();

const t0 = Date.now();
const created = await Promise.all(Array.from({ length: N }, (_, i) => {
  const who = callers[i % callers.length];
  return placeOrder(who.token, orderBody(who.customerId, [[SKU_N, 1]]), `oversell-${Date.now()}-${i}`).then((r) => ({ ...r, who }));
}));
const burstMs = Date.now() - t0;
const accepted = created.filter((r) => r.status === 202);
const http = created.reduce((m, r) => ((m[r.status] = (m[r.status] ?? 0) + 1), m), {});
console.log(`burst finished in ${burstMs} ms; HTTP statuses: ${JSON.stringify(http)}`);

// wait until every accepted order is terminal OR settled in CONFIRMED (fulfillment continues asynchronously)
const finals = new Map();
const deadline = Date.now() + 120000;
while (Date.now() < deadline) {
  let open = 0;
  for (const r of accepted) {
    if (finals.has(r.json.id)) continue;
    const o = (await call('GET', `/api/v1/orders/${r.json.id}`, { token: r.who.token })).json;
    if (o && TERMINAL.has(o.status)) finals.set(r.json.id, o.status); else open++;
  }
  if (open === 0) break;
  await sleep(500);
}
sampling = false; await sampler;

const by = {};
for (const s of finals.values()) by[s] = (by[s] ?? 0) + 1;
const succeeded = (by.FULFILLED ?? 0);
let end = await stock();
for (let i = 0; i < 40 && (start.onHand - end.onHand !== succeeded || end.reserved !== start.reserved); i++) { await sleep(500); end = await stock(); } // shipping is applied asynchronously after FulfillmentCompleted
const res = (await call('GET', `/api/v1/inventory/reservations?size=100`, { token: staff })).json?.content ?? [];
const mine = res.filter((r) => accepted.some((a) => a.json.id === r.orderId));
const active = mine.filter((r) => r.status === 'RESERVED' || r.status === 'COMMITTED').length;

console.log(`final order statuses: ${JSON.stringify(by)} (accepted ${accepted.length}, terminal ${finals.size})`);
console.log(`inventory after: onHand=${end.onHand} reserved=${end.reserved}; sampled ${samples} times, max(shipped+reserved)=${maxCommitted}, min(available)=${minAvailable}`);
console.log(`reservations for these orders: ${mine.length} rows, ${active} RESERVED/COMMITTED`);

ck.check(accepted.length === N, `all ${N} orders accepted (202) by the API`, JSON.stringify(http));
ck.check(finals.size === accepted.length, 'every accepted order reached a terminal state', `${finals.size}/${accepted.length}`);
ck.check(maxCommitted <= cap, `never oversold: max(shipped+reserved) ${maxCommitted} <= starting available ${cap}`);
ck.check(minAvailable >= 0, `available never negative (min ${minAvailable})`);
ck.check(succeeded === Math.min(cap, N), `exactly ${Math.min(cap, N)} orders FULFILLED`, `got ${succeeded}`);
ck.check((by.REJECTED ?? 0) === accepted.length - succeeded, 'all other orders are REJECTED', JSON.stringify(by));
ck.check(active <= cap, `reservation rows RESERVED/COMMITTED (${active}) <= ${cap}`);
ck.check(start.onHand - end.onHand === succeeded && end.reserved === start.reserved, 'stock ledger matches: onHand dropped by #fulfilled, reserved back to start', `onHand ${start.onHand}->${end.onHand}, reserved ${start.reserved}->${end.reserved}`);
console.log(ck.failures === 0 ? '\nOVERSELL CHECK PASSED' : `\nOVERSELL CHECK FAILED (${ck.failures})`);
process.exit(ck.failures === 0 ? 0 : 1);

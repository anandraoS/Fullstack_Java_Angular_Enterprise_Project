// API-level end-to-end scenarios against the LIVE stack through the gateway (complements the Playwright UI suite).
//   OMS_DEMO_PASSWORD=... node scripts/live-flows.mjs          (env OMS_BASE_URL default http://localhost:18080)
// Scenarios: happy path -> FULFILLED (+history, saga, reservation, fulfillment, notifications, audit); insufficient stock
// (SKU-1020 stock 0, SKU-1019 over-ask); idempotency replay / reuse; cancel at several states; FAILTOWN failed fulfillment ->
// COMPENSATION_PENDING -> FAILED with stock released; cross-customer 404; notification retry path (customer3, fail.example.test);
// ops/messaging + ops/health + audit endpoints. Exit code 1 on any failed check.
import crypto from 'node:crypto';
import { call, login, orderBody, placeOrder, waitForStatus, sku, sleep, makeChecker, CUSTOMER_1, CUSTOMER_2, CUSTOMER_3 } from './lib/oms-client.mjs';

const ck = makeChecker();
const { check } = ck;
const c1 = await login('customer1');
const c2 = await login('customer2');
const op = (await login('operator1')).token;
const ad = (await login('admin1')).token;
const hist = async (token, id) => (await call('GET', `/api/v1/orders/${id}/history`, { token })).json ?? [];
const path = (h) => h.map((x) => x.toStatus).join('>');
const avail = async (n) => { const p = (await call('GET', `/api/v1/inventory/${sku(n)}`, { token: op })).json; return p.onHand - p.reserved; };

// ---------------------------------------------------------------- 1 happy path
console.log('\n## 1 happy path');
const a0 = await avail(1);
const happy = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[1, 2]]));
check(happy.status === 202 && happy.json.status === 'CREATED' && happy.headers.get('location'), 'POST /orders -> 202 CREATED + Location', `HTTP ${happy.status}`);
const hid = happy.json.id;
const hf = await waitForStatus(c1.token, hid, 'FULFILLED');
check(hf?.status === 'FULFILLED', 'order reaches FULFILLED', `status ${hf?.status}`);
check(Number(hf?.totalAmount) > 0 && hf?.items?.[0]?.sku === 'SKU-1001', 'order priced by validation (total > 0, sku resolved)', `total ${hf?.totalAmount}`);
const hh = await hist(c1.token, hid);
check(path(hh) === 'CREATED>VALIDATED>INVENTORY_RESERVED>CONFIRMED>FULFILLED' || path(hh) === 'VALIDATED>INVENTORY_RESERVED>CONFIRMED>FULFILLED', 'status history path', path(hh));
const saga = (await call('GET', `/api/v1/admin/saga/${hid}`, { token: op })).json;
check(saga?.state === 'COMPLETED', 'saga COMPLETED', `${saga?.state} steps=${saga?.steps?.length}`);
check((await call('GET', `/api/v1/admin/saga/${hid}`, { token: c1.token })).status === 403, 'customer cannot read saga (403)');
const resv = (await call('GET', `/api/v1/inventory/reservations?orderId=${hid}`, { token: op })).json?.content?.[0];
check(resv?.status === 'COMMITTED' && resv?.shippedAt, 'reservation COMMITTED and shipped', `${resv?.status} shippedAt=${resv?.shippedAt}`);
await sleep(1000);
check((await avail(1)) === a0 - 2, 'SKU-1001 available dropped by 2', `${a0} -> ${await avail(1)}`);
const ful = (await call('GET', `/api/v1/fulfillments?orderId=${hid}`, { token: op })).json?.content?.[0];
check(ful?.status === 'COMPLETED' && /^OMS-[0-9A-F]{12}$/.test(ful?.trackingNumber ?? ''), 'fulfillment COMPLETED with tracking number', `${ful?.status} ${ful?.trackingNumber}`);
check((await call('GET', `/api/v1/fulfillments?orderId=${hid}`, { token: c1.token })).status === 403, 'customer cannot read fulfillments (403)');
let notes = [];
for (let i = 0; i < 30; i++) { notes = (await call('GET', `/api/v1/notifications?orderId=${hid}`, { token: op })).json?.content ?? []; if (notes.filter((n) => n.status === 'SENT').length >= 5) break; await sleep(1000); }
const tmpl = notes.map((n) => n.template).sort();
check(['FULFILLMENT_COMPLETED', 'ORDER_CONFIRMED', 'ORDER_CREATED', 'ORDER_FULFILLED'].every((t) => tmpl.includes(t)) && notes.every((n) => n.status === 'SENT'), 'notifications recorded and SENT', `${tmpl.join(',')}`);
const myNotes = (await call('GET', `/api/v1/notifications?orderId=${hid}`, { token: c1.token })).json?.content ?? [];
check(myNotes.length === notes.length, 'customer sees own notifications for the order', `${myNotes.length}/${notes.length}`);
check(!JSON.stringify(notes).includes('@'), 'notification API never returns the recipient address');
const aud = (await call('GET', `/api/v1/ops/order/audit?entityType=Order&entityId=${hid}`, { token: op })).json;
check((aud?.content?.length ?? 0) >= 4, 'order audit entries recorded', `${aud?.content?.length} entries, actions ${[...new Set((aud?.content ?? []).map((e) => e.action))].join(',')}`);
check((await call('GET', `/api/v1/ops/order/audit?entityType=Order&entityId=${hid}`, { token: c1.token })).status === 403, 'customer cannot read audit (403)');

// ---------------------------------------------------------------- 2 insufficient stock
console.log('\n## 2 insufficient stock');
const s20 = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[20, 1]]));
const s20f = await waitForStatus(c1.token, s20.json.id, 'REJECTED');
check(s20f?.status === 'REJECTED' && /stock/i.test(s20f.statusReason ?? ''), 'SKU-1020 (stock 0) -> REJECTED with reason', `${s20f?.status} "${s20f?.statusReason}"`);
const before19 = await avail(19);
const s19 = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[19, before19 + 1]]));
const s19f = await waitForStatus(c1.token, s19.json.id, 'REJECTED');
check(s19f?.status === 'REJECTED' && (await avail(19)) === before19, `SKU-1019 over-ask (${before19 + 1} > ${before19}) -> REJECTED, stock untouched`, `${s19f?.status} "${s19f?.statusReason}"`);
const mixed = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[1, 1], [20, 1]]));
const mixedF = await waitForStatus(c1.token, mixed.json.id, 'REJECTED');
check(mixedF?.status === 'REJECTED', 'mixed order (ok line + out-of-stock line) is all-or-nothing REJECTED', mixedF?.status);
const rej = (await call('GET', '/api/v1/admin/orders/failed?size=100', { token: op })).json?.content ?? [];
check(rej.some((o) => o.id === s20.json.id), 'rejected order appears in /admin/orders/failed');

// ---------------------------------------------------------------- 3 idempotency
console.log('\n## 3 idempotency');
const key = `idem-${crypto.randomUUID()}`;
const body = orderBody(CUSTOMER_1, [[3, 1]]);
const i1 = await placeOrder(c1.token, body, key);
const i2 = await placeOrder(c1.token, body, key);
check(i2.status === 202 && i2.json.id === i1.json.id && i2.headers.get('idempotent-replay') === 'true', 'replay: same key + same body -> same order, Idempotent-Replay: true');
const i3 = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[3, 2]]), key);
check(i3.status === 422 && i3.json?.code === 'IDEMPOTENCY_KEY_REUSED', 'same key + different body -> 422 IDEMPOTENCY_KEY_REUSED', `${i3.status} ${i3.json?.code}`);
const i4 = await call('POST', '/api/v1/orders', { token: c1.token, body });
check(i4.status === 400 && i4.json?.code === 'IDEMPOTENCY_KEY_REQUIRED', 'missing key -> 400 IDEMPOTENCY_KEY_REQUIRED');
const par = await Promise.all(Array.from({ length: 8 }, () => placeOrder(c1.token, orderBody(CUSTOMER_1, [[3, 1]]), 'par-' + key)));
const ids = new Set(par.filter((r) => r.status === 202).map((r) => r.json.id));
check(ids.size === 1, '8 PARALLEL requests with one key create exactly ONE order', `distinct ids ${ids.size}, statuses ${par.map((r) => r.status).join(',')}`);
await waitForStatus(c1.token, i1.json.id, 'FULFILLED');

// ---------------------------------------------------------------- 4 cancel
console.log('\n## 4 cancel at various states');
const k1 = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[5, 1]]));
const k1c = await call('POST', `/api/v1/orders/${k1.json.id}/cancel`, { token: c1.token, body: { reason: 'live test early cancel' } });
const k1f = await waitForStatus(c1.token, k1.json.id, 'CANCELLED', 60);
check([200, 409].includes(k1c.status) && k1f?.status === 'CANCELLED', 'cancel immediately after create ends CANCELLED', `cancel HTTP ${k1c.status}, final ${k1f?.status}, path ${path(await hist(c1.token, k1.json.id))}`);
const k2 = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[6, 1]]));
const a6 = await avail(6);
const k2s = await waitForStatus(c1.token, k2.json.id, ['CONFIRMED', 'FULFILLED'], 30);
const k2c = await call('POST', `/api/v1/orders/${k2.json.id}/cancel`, { token: c1.token, body: { reason: 'live test cancel when confirmed' } });
const k2f = await waitForStatus(c1.token, k2.json.id, ['CANCELLED', 'FULFILLED'], 60);
const k2h = path(await hist(c1.token, k2.json.id));
if (k2c.status === 200) {
  check(k2f?.status === 'CANCELLED' && k2h.includes('COMPENSATION_PENDING'), 'cancel at CONFIRMED -> COMPENSATION_PENDING -> CANCELLED', k2h);
  await sleep(500);
  const r6 = (await call('GET', `/api/v1/inventory/reservations?orderId=${k2.json.id}`, { token: op })).json?.content?.[0];
  check(r6?.status === 'RELEASED', 'cancelled order reservation RELEASED, stock restored', `${r6?.status}; available SKU-1006 ${await avail(6)} (while reserved ${a6})`);
} else check(false, 'cancel at CONFIRMED accepted', `cancel HTTP ${k2c.status} ${k2c.json?.code}, order was ${k2s?.status}, final ${k2f?.status} (timing)`);
const k3 = await call('POST', `/api/v1/orders/${hid}/cancel`, { token: c1.token, body: { reason: 'too late' } });
check(k3.status === 409 && k3.json?.code === 'INVALID_STATE_TRANSITION', 'cancel a FULFILLED order -> 409 INVALID_STATE_TRANSITION', `${k3.status} ${k3.json?.code}`);
const k4 = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[7, 1]]));
const k4c = await call('POST', `/api/v1/orders/${k4.json.id}/cancel`, { token: op, body: { reason: 'operator cancel' } });
check(k4c.status === 200 || k4c.status === 409, 'operator can cancel any order', `HTTP ${k4c.status}`);
await waitForStatus(c1.token, k4.json.id, ['CANCELLED', 'FULFILLED'], 60);

// ---------------------------------------------------------------- 5 FAILTOWN
console.log('\n## 5 failed fulfillment (FAILTOWN)');
const a4 = await avail(4);
const ft = await placeOrder(c1.token, orderBody(CUSTOMER_1, [[4, 1]], 'FAILTOWN'));
const ftf = await waitForStatus(c1.token, ft.json.id, 'FAILED', 120);
const fth = path(await hist(c1.token, ft.json.id));
check(ftf?.status === 'FAILED', 'FAILTOWN order ends FAILED', `${ftf?.status} "${ftf?.statusReason}"`);
check(fth.includes('CONFIRMED>COMPENSATION_PENDING>FAILED'), 'history shows COMPENSATION_PENDING before FAILED', fth);
const ftr = (await call('GET', `/api/v1/inventory/reservations?orderId=${ft.json.id}`, { token: op })).json?.content?.[0];
check(ftr?.status === 'RELEASED' && (await avail(4)) === a4, 'stock released after failure', `reservation ${ftr?.status} (${ftr?.releaseReason}); available ${a4} -> ${await avail(4)}`);
const ftfu = (await call('GET', `/api/v1/fulfillments?orderId=${ft.json.id}`, { token: op })).json?.content?.[0];
check(ftfu?.status === 'FAILED' && ftfu?.attempts >= 1, 'fulfillment FAILED with attempts', `${ftfu?.status} attempts=${ftfu?.attempts} "${ftfu?.failureReason}"`);
const retryTerminal = await call('POST', `/api/v1/admin/orders/${ft.json.id}/retry`, { token: op });
check(retryTerminal.status === 409, 'retry of a terminal (FAILED) order -> 409', `HTTP ${retryTerminal.status}`);
const failedList = (await call('GET', '/api/v1/admin/orders/failed?size=100', { token: op })).json?.content ?? [];
check(failedList.some((o) => o.id === ft.json.id), 'FAILED order listed in /admin/orders/failed');

// ---------------------------------------------------------------- 6 authorization
console.log('\n## 6 authorization / cross-customer');
check((await call('GET', `/api/v1/orders/${hid}`, { token: c2.token })).status === 404, 'customer2 GET customer1 order -> 404 (not 403)');
check((await call('GET', `/api/v1/orders/${hid}/history`, { token: c2.token })).status === 404, 'customer2 GET history of customer1 order -> 404');
check((await call('POST', `/api/v1/orders/${hid}/cancel`, { token: c2.token, body: {} })).status === 404, 'customer2 cancel customer1 order -> 404');
const lst = (await call('GET', `/api/v1/orders?customerId=${CUSTOMER_1}&size=100`, { token: c2.token })).json?.content ?? [];
check(lst.every((o) => o.customerId === CUSTOMER_2), 'customer2 listing with customerId=customer1 returns only own orders (filter forced)', `${lst.length} rows`);
const forge = await placeOrder(c2.token, orderBody(CUSTOMER_1, [[1, 1]]));
check(forge.status === 403, 'customer2 creating an order for customer1 -> 403', `HTTP ${forge.status}`);
check((await call('GET', '/api/v1/customers/' + CUSTOMER_1, { token: c2.token })).status === 404, 'customer2 GET customer1 profile -> 404');
check((await call('GET', '/api/v1/inventory', { token: c1.token })).status === 403, 'customer cannot read /inventory (403)');
check((await call('POST', '/api/v1/internal/products/validate', { token: ad, body: { items: [] } })).status === 404, '/internal/** not reachable through the gateway (404)');
const prods = (await call('GET', '/api/v1/products?size=100', { token: c1.token })).json?.content ?? [];
check(prods.length > 0 && prods.every((p) => p.onHand === undefined && p.reserved === undefined && p.available !== undefined), 'customers see availability only (no onHand/reserved)', `${prods.length} products`);

// ---------------------------------------------------------------- 7 notification retry path
console.log('\n## 7 notification retry path (customer3 = fail.example.test)');
const n3 = await placeOrder(op, orderBody(CUSTOMER_3, [[8, 1]]));
check(n3.status === 202, 'operator places order for customer3', `HTTP ${n3.status}`);
let n3notes = []; let seenRetrying = null;
for (let i = 0; i < 240; i++) { // poll from the start so the transient RETRYING state + lastError can be observed (cleared on success)
  n3notes = (await call('GET', `/api/v1/notifications?orderId=${n3.json.id}`, { token: op })).json?.content ?? [];
  seenRetrying ??= n3notes.find((n) => n.status === 'RETRYING' && n.lastError) ?? null;
  if (n3notes.length >= 5 && n3notes.every((n) => n.status === 'SENT' || n.status === 'FAILED')) break;
  await sleep(500);
}
await waitForStatus(op, n3.json.id, 'FULFILLED', 90);
const retried = n3notes.filter((n) => n.attempts > 1);
check(retried.length > 0 && n3notes.every((n) => n.status === 'SENT'), 'fail.example.test notifications succeed only after retries (simulated failures)', n3notes.map((n) => `${n.template}:${n.status}/${n.attempts}`).join(' '));
check(Boolean(seenRetrying), 'RETRYING state with lastError was observable while retrying (cleared after success)', seenRetrying ? `${seenRetrying.template} attempts=${seenRetrying.attempts} lastError="${seenRetrying.lastError}"` : 'never observed');

// ---------------------------------------------------------------- 8 ops
console.log('\n## 8 ops / messaging / health');
for (const svc of ['order', 'inventory', 'fulfillment', 'notification']) {
  const m = await call('GET', `/api/v1/ops/${svc}/messaging`, { token: op });
  check(m.status === 200 && m.json.processedEvents > 0 && m.json.deadLettered === 0, `ops/${svc}/messaging processed>0, no DLT`, `processed=${m.json?.processedEvents} dup=${m.json?.duplicatesIgnored} retries=${m.json?.retries} dlt=${m.json?.deadLettered} outboxPending=${m.json?.outboxPending} lagAvailable=${m.json?.lagAvailable} group=${m.json?.consumerGroup}`);
}
const health = (await call('GET', '/api/v1/ops/health', { token: op })).json;
check(health?.status === 'UP' && Object.values(health.services ?? {}).every((s) => s.status === 'UP'), 'gateway /ops/health aggregate UP', JSON.stringify(health));
check((await call('GET', '/api/v1/ops/health', { token: c1.token })).status === 403, 'customer cannot read /ops/health (403)');
const recon = await call('POST', '/api/v1/admin/reconciliation/run', { token: ad });
check(recon.status === 200 && typeof recon.json?.examined === 'number', 'admin reconciliation runs', JSON.stringify({ examined: recon.json?.examined, repaired: recon.json?.repaired }));
check((await call('POST', '/api/v1/admin/reconciliation/run', { token: op })).status === 403, 'operator cannot run reconciliation (403)');

console.log(ck.failures === 0 ? `\nLIVE FLOWS PASSED (${ck.passes} checks)` : `\nLIVE FLOWS FAILED (${ck.failures} failed, ${ck.passes} passed)`);
process.exit(ck.failures === 0 ? 0 : 1);

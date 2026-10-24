// LIVE restart-resilience demo: stop a consumer service (fulfillment-service) while an order is in flight, start it again and show
// the order still reaches FULFILLED (the StartFulfillment command waits durably in Kafka; the committed offset is not advanced).
//   OMS_DEMO_PASSWORD=... node tests/e2e/resilience-live.mjs        (needs a stack started with scripts/run-local.sh)
// Scenario A (deterministic): service stopped BEFORE the order is placed -> order must park in CONFIRMED -> restart -> FULFILLED.
// Scenario B (best effort, honest about timing): stop is triggered the moment the order is seen CONFIRMED; fulfillment steps
//   take ~4.5 s (oms.fulfillment.step-delay-ms 1500 x 3) and a graceful stop can take longer, so B may report "finished before
//   the stop took effect"; that is printed as INFO, not as a pass.
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { call, login, orderBody, placeOrder, sleep, makeChecker, CUSTOMER_1 } from '../../scripts/lib/oms-client.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ctl = (cmd, svc) => new Promise((resolve) => {
  const p = spawn('bash', [path.join(ROOT, 'scripts/service-ctl.sh'), cmd, svc], { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = ''; p.stdout.on('data', (d) => (out += d)); p.stderr.on('data', (d) => (out += d));
  p.on('close', (code) => resolve({ code, out: out.trim() }));
});
const ck = makeChecker();
const { token } = await login('customer1');
const op = (await login('operator1')).token;
const statusOf = async (id) => (await call('GET', `/api/v1/orders/${id}`, { token })).json?.status;
const fulfillmentUp = async () => (await call('GET', '/api/v1/ops/health', { token: op })).json?.services?.fulfillment?.status;
const waitStatus = async (id, want, secs) => { const end = Date.now() + secs * 1000; let s; while (Date.now() < end) { s = await statusOf(id); if (want.includes(s)) return s; await sleep(400); } return s; };

console.log('--- Scenario A: stop fulfillment-service, place order, restart');
let r = await ctl('stop', 'fulfillment-service'); console.log(r.out);
await sleep(1500);
ck.check((await fulfillmentUp()) === 'DOWN', 'gateway ops health reports fulfillment DOWN', `status ${await fulfillmentUp()}`);
const a = await placeOrder(token, orderBody(CUSTOMER_1, [[1, 1]]));
ck.check(a.status === 202, 'order accepted while fulfillment is down', `HTTP ${a.status}`);
const parked = await waitStatus(a.json.id, ['CONFIRMED', 'FULFILLED', 'FAILED'], 30);
await sleep(8000); // longer than a complete fulfillment run (~4.5 s) -> must still be parked
const stillParked = await statusOf(a.json.id);
ck.check(parked === 'CONFIRMED' && stillParked === 'CONFIRMED', 'order is parked in CONFIRMED while the consumer is down', `${parked} -> ${stillParked} after 8 s`);
const t0 = Date.now();
r = await ctl('start', 'fulfillment-service'); console.log(r.out);
ck.check(r.code === 0, 'fulfillment-service restarted and ready');
const done = await waitStatus(a.json.id, ['FULFILLED', 'FAILED'], 90);
ck.check(done === 'FULFILLED', 'parked order completes after restart (no manual action)', `${done} ${((Date.now() - t0) / 1000).toFixed(1)} s after the start command`);
const f = (await call('GET', `/api/v1/fulfillments?orderId=${a.json.id}`, { token: op })).json?.content ?? [];
ck.check(f.length === 1 && f[0].status === 'COMPLETED', 'exactly one fulfillment row, COMPLETED (no duplicate from redelivery)', `rows=${f.length} status=${f[0]?.status}`);

console.log('\n--- Scenario B: stop triggered when the order is first seen CONFIRMED (best effort)');
const b = await placeOrder(token, orderBody(CUSTOMER_1, [[2, 1]]));
let seen = null; for (let i = 0; i < 100 && seen !== 'CONFIRMED'; i++) { seen = await statusOf(b.json.id); if (['FULFILLED', 'FAILED', 'REJECTED'].includes(seen)) break; await sleep(100); }
const stopP = ctl('stop', 'fulfillment-service');
await stopP;
const atStop = await statusOf(b.json.id);
console.log(`INFO  order status when the stop completed: ${atStop}`);
const midFlow = atStop !== 'FULFILLED';
if (!midFlow) console.log('INFO  order finished before the stop took effect (timing); scenario A is the deterministic proof');
r = await ctl('start', 'fulfillment-service'); console.log(r.out);
const doneB = await waitStatus(b.json.id, ['FULFILLED', 'FAILED'], 90);
ck.check(doneB === 'FULFILLED', 'order completes after restart', `${doneB} (stopped while ${atStop}${midFlow ? ', i.e. mid-flow' : ''})`);
const fb = (await call('GET', `/api/v1/fulfillments?orderId=${b.json.id}`, { token: op })).json?.content ?? [];
ck.check(fb.length === 1, 'no duplicate fulfillment rows', `rows=${fb.length}`);
console.log(ck.failures === 0 ? '\nRESILIENCE CHECK PASSED' : `\nRESILIENCE CHECK FAILED (${ck.failures})`);
process.exit(ck.failures === 0 ? 0 : 1);

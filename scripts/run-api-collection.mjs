// Minimal Postman-collection runner (no Postman/newman needed): executes tests/api-collection/oms.postman_collection.json against the
// live gateway, in order, with the collection's variables, `{{$guid}}` and the Idempotency-Key pre-request logic, and checks the expected status
// codes declared in each request's test script. Purpose: prove the committed collection is correct against the real stack.
//   OMS_DEMO_PASSWORD=... node scripts/run-api-collection.mjs [collection.json]     (OMS_BASE_URL default http://localhost:18080)
// It interprets only what scripts/gen-api-collection.mjs generates (status expectation + collectionVariables.set from response fields).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const file = path.resolve(process.argv[2] ?? path.join(ROOT, 'tests/api-collection/oms.postman_collection.json'));
const col = JSON.parse(fs.readFileSync(file, 'utf8'));
const vars = Object.fromEntries(col.variable.map((v) => [v.key, v.value]));
vars.baseUrl = (process.env.OMS_BASE_URL ?? vars.baseUrl).replace(/\/$/, '');
if (!process.env.OMS_DEMO_PASSWORD) { console.error('OMS_DEMO_PASSWORD is not set'); process.exit(2); }
vars.demoPassword = process.env.OMS_DEMO_PASSWORD;
const sub = (s) => String(s).replace(/\{\{\$guid\}\}/g, () => crypto.randomUUID()).replace(/\{\{(\w+)\}\}/g, (_, k) => vars[k] ?? '');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
for (const folder of col.item) {
  console.log(`\n# ${folder.name}`);
  for (const it of folder.item) {
    const req = it.request;
    const headers = {};
    for (const h of req.header) headers[h.key] = sub(h.value);
    const pre = (it.event ?? []).find((e) => e.listen === 'prerequest');
    if (pre && headers['Idempotency-Key'] !== undefined) { // mirrors the pre-request script: fresh key, remembered for the "reuse" requests
      headers['Idempotency-Key'] = 'pm-' + crypto.randomUUID();
      vars.lastIdempotencyKey = headers['Idempotency-Key'];
    }
    if (req.auth?.type === 'bearer') headers.Authorization = 'Bearer ' + sub(req.auth.bearer[0].value);
    const body = req.body?.raw ? sub(req.body.raw) : undefined;
    const res = await fetch(sub(req.url.raw), { method: req.method, headers, body, signal: AbortSignal.timeout(20000) });
    const text = await res.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
    const script = ((it.event ?? []).find((e) => e.listen === 'test')?.script.exec ?? []).join('\n');
    const expected = (/pm\.expect\(\[([\d,]+)\]\)/.exec(script)?.[1] ?? '').split(',').map(Number).filter(Boolean);
    const ok = expected.includes(res.status);
    if (res.status < 300) for (const m of script.matchAll(/collectionVariables\.set\('(\w+)', pm\.response\.json\(\)\.(\w+)\)/g)) vars[m[1]] = json?.[m[2]];
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${it.name} -> HTTP ${res.status} (expected ${expected.join('/')})`);
    ok ? pass++ : fail++;
    // let asynchronous saga steps progress before the next request that depends on them
    if (it.name.startsWith('Create order (happy path')) await sleep(12000);
  }
}
console.log(`\nCOLLECTION RUN: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);

// Prints every JSON log line (all services, from .run/*.log of a stack started with `run-local.sh --json-logs`) that belongs to
// one correlation id or one order id, ordered by time, with PII masked. Used to produce docs/validation/correlation-trace-sample.md.
//   node scripts/correlation-trace.mjs <correlationId> [orderId]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const [cid, oid] = process.argv.slice(2);
if (!cid) { console.error('usage: correlation-trace.mjs <correlationId> [orderId]'); process.exit(2); }
const SERVICES = ['api-gateway', 'order-service', 'inventory-service', 'fulfillment-service', 'notification-service'];
const maskEmail = (s) => s.replace(/\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b/g, '$1***@$2');
const rows = [];
for (const svc of SERVICES) {
  const file = path.join(ROOT, '.run', `${svc}.log`);
  if (!fs.existsSync(file)) continue;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.startsWith('{')) continue;
    let j; try { j = JSON.parse(line); } catch { continue; }
    // lines of the order's own flow: same correlation id; lines that only mention the order id but belong to ANOTHER request (e.g. the
    // client's status polling, each with its own id) are left out; lines without any correlation id are kept when they mention the order
    if (j.correlationId === cid || (oid && !j.correlationId && String(j.message ?? '').includes(oid))) rows.push({ svc, j });
  }
}
rows.sort((a, b) => String(a.j.timestamp).localeCompare(String(b.j.timestamp)));
for (const { svc, j } of rows) {
  const out = { timestamp: j.timestamp, level: j.level, service: j.service ?? svc, correlationId: j.correlationId ?? null, traceId: j.traceId ?? null, spanId: j.spanId ?? null, logger: j.logger, message: maskEmail(String(j.message)) };
  console.log(JSON.stringify(out));
}
console.error(`${rows.length} line(s), services: ${[...new Set(rows.map((r) => r.svc))].join(', ')}`);

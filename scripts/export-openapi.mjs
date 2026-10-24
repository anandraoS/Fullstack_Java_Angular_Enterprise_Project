// NOTE: the gateway (reactive, no springdoc) has no /v3/api-docs; specs/openapi/api-gateway.json comes from scripts/gen-gateway-openapi.mjs.
// Exports each service's OpenAPI document from a RUNNING stack into specs/openapi/<service>.json.
// Usage: node scripts/export-openapi.mjs [--out specs/openapi] [--check]
//   --out DIR   output directory (default: <repo>/specs/openapi)
//   --check     do not write; exit 1 if a service document differs from the committed file (drift check for CI)
// Base URLs (the services' own ports from contract section 2; /v3/api-docs is not routed through the gateway):
//   OMS_ORDER_URL (http://localhost:18081)  OMS_INVENTORY_URL (:18082)  OMS_FULFILLMENT_URL (:18083)  OMS_NOTIFICATION_URL (:18084)
// No credentials are needed: /v3/api-docs is public in every service. Output is normalised (sorted keys, 2-space indent,
// trailing newline) so repeated exports of an unchanged API produce no diff. Exits 1 if any service fails.
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const flagValue = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const check = args.includes('--check');
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outDir = path.resolve(flagValue('--out') ?? path.join(repoRoot, 'specs', 'openapi'));

const services = [
  { name: 'order-service', env: 'OMS_ORDER_URL', fallback: 'http://localhost:18081' },
  { name: 'inventory-service', env: 'OMS_INVENTORY_URL', fallback: 'http://localhost:18082' },
  { name: 'fulfillment-service', env: 'OMS_FULFILLMENT_URL', fallback: 'http://localhost:18083' },
  { name: 'notification-service', env: 'OMS_NOTIFICATION_URL', fallback: 'http://localhost:18084' },
];

const sortKeys = (value) => {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((k) => [k, sortKeys(value[k])]));
  }
  return value;
};

async function fetchSpec(base) {
  const url = new URL('/v3/api-docs', base);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error(`unsupported URL scheme ${url.protocol}`);
  const res = await fetch(url, { headers: { accept: 'application/json' }, signal: AbortSignal.timeout(15000), redirect: 'error' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const doc = await res.json();
  if (!doc || typeof doc.openapi !== 'string' || typeof doc.paths !== 'object') throw new Error('response is not an OpenAPI document');
  return doc;
}

let failed = 0;
if (!check) await mkdir(outDir, { recursive: true });
for (const svc of services) {
  const base = process.env[svc.env] ?? svc.fallback;
  const file = path.join(outDir, `${svc.name}.json`);
  try {
    const doc = sortKeys(await fetchSpec(base));
    // servers[] carries the host the export ran against; keep the committed spec environment independent
    delete doc.servers;
    const text = `${JSON.stringify(doc, null, 2)}\n`;
    const paths = Object.keys(doc.paths).length;
    if (check) {
      const existing = await readFile(file, 'utf8').catch(() => null);
      if (existing !== text) {
        failed++;
        console.error(`DRIFT   ${svc.name}: ${file} is ${existing === null ? 'missing' : 'out of date'}`);
      } else {
        console.log(`ok      ${svc.name} (${paths} paths)`);
      }
    } else {
      const tmp = `${file}.tmp`;
      await writeFile(tmp, text, 'utf8');
      await rename(tmp, file);
      console.log(`wrote   ${path.relative(repoRoot, file)} (${paths} paths, from ${new URL(base).origin})`);
    }
  } catch (err) {
    failed++;
    console.error(`FAILED  ${svc.name} (${new URL(base).origin}): ${err.message}`);
  }
}
if (failed > 0) {
  console.error(`${failed} of ${services.length} services failed. Is the stack running? (scripts/run-local.sh)`);
  process.exit(1);
}

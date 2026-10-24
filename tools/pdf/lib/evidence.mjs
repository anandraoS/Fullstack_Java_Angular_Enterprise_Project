// Builds the "what was executed" evidence markdown from files on disk, so every PDF rebuild reflects the latest runs.
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, read, exists } from './common.mjs';

function attr(xml, name) {
  const m = new RegExp(`<testsuite[^>]*\\s${name}="([^"]*)"`).exec(xml);
  return m ? Number(m[1]) : 0;
}

function collect(dir) {
  const r = { suites: 0, tests: 0, failures: 0, errors: 0, skipped: 0, files: [] };
  if (!fs.existsSync(dir)) return null;
  for (const f of fs.readdirSync(dir)) {
    if (!/^TEST-.*\.xml$/.test(f)) continue;
    const xml = fs.readFileSync(path.join(dir, f), 'utf8').slice(0, 4000);
    r.suites++;
    r.tests += attr(xml, 'tests');
    r.failures += attr(xml, 'failures');
    r.errors += attr(xml, 'errors');
    r.skipped += attr(xml, 'skipped');
    r.files.push(fs.statSync(path.join(dir, f)).mtimeMs);
  }
  return r.suites ? r : null;
}

const MODULES = [
  ['platform-common', 'services/platform-common'],
  ['order-service', 'services/order-service'],
  ['inventory-service', 'services/inventory-service'],
  ['fulfillment-service', 'services/fulfillment-service'],
  ['notification-service', 'services/notification-service'],
  ['api-gateway', 'gateway'],
  ['platform-test-support', 'services/platform-test-support'],
  ['local-stack', 'services/local-stack'],
];

export function testStats() {
  const rows = [];
  const tot = { u: 0, uf: 0, us: 0, i: 0, if: 0, is: 0 };
  for (const [name, dir] of MODULES) {
    const u = collect(path.join(ROOT, dir, 'target', 'surefire-reports'));
    const i = collect(path.join(ROOT, dir, 'target', 'failsafe-reports'));
    if (!u && !i) continue;
    const when = new Date(Math.max(...[...(u?.files ?? []), ...(i?.files ?? [])])).toISOString().slice(0, 16).replace('T', ' ');
    rows.push(`| ${name} | ${u ? `${u.tests} run, ${u.failures + u.errors} failed, ${u.skipped} skipped` : 'no report'} | ${i ? `${i.tests} run, ${i.failures + i.errors} failed, ${i.skipped} skipped` : 'no report'} | ${when} UTC |`);
    if (u) { tot.u += u.tests; tot.uf += u.failures + u.errors; tot.us += u.skipped; }
    if (i) { tot.i += i.tests; tot.if += i.failures + i.errors; tot.is += i.skipped; }
  }
  return { rows, tot };
}

export function testStatsMarkdown() {
  const { rows, tot } = testStats();
  if (!rows.length) return '_No surefire or failsafe reports were found under the module target directories when this PDF was built._';
  return [
    '### Backend test results found on disk (surefire = unit and slice tests, failsafe = integration tests)',
    '',
    '| Module | Surefire | Failsafe (integration) | Newest report |',
    '|---|---|---|---|',
    ...rows,
    `| **Total** | **${tot.u} run, ${tot.uf} failed, ${tot.us} skipped** | **${tot.i} run, ${tot.if} failed, ${tot.is} skipped** | |`,
    '',
    'Counts come from the JUnit XML in `target/surefire-reports` and `target/failsafe-reports`. A report shows what ran at the time of the last Maven run on that module, which may differ from the current source. Skipped integration tests are normally the Testcontainers PostgreSQL and Kafka classes, skipped because Docker is not installed on the development machine; they prove nothing about PostgreSQL behaviour.',
  ].join('\n');
}

/** The integration agent's validation report, if present; otherwise an explicit placeholder. */
export function validationMarkdown() {
  const rel = 'docs/validation/validation-report.md';
  if (exists(rel)) {
    return ['### Validation report (docs/validation/validation-report.md)', '', read(rel).replace(/^#\s+.*\n+/, '')].join('\n');
  }
  return [
    '### Validation report',
    '',
    '> **Placeholder, to be refreshed.** `docs/validation/validation-report.md` did not exist when this PDF was built. It is produced by the integration run (live local stack, smoke test, API examples). Re-run `node build-pdf1.mjs` in `tools/pdf` after that report exists and this section is replaced by its content. Until then, treat anything not listed in the backend test table as **documented / not executed**.',
  ].join('\n');
}

/** Appendix B: the integration defects (real, found by running the stack), taken from docs/validation/integration-defects.md. */
export function defectsMarkdown() {
  const rel = 'docs/validation/integration-defects.md';
  if (!exists(rel)) return '_docs/validation/integration-defects.md was not found when this PDF was built._';
  const body = read(rel).replace(/^#\s+.*\n+/, '');
  return [
    "Eleven defects were found by running the real stack end to end after every module had a green test suite. They are the most useful interview material in this project, because each one is a concrete answer to 'tell me about a bug your tests did not catch'. The text below is the integration run's own record; the closing list names issues that were NOT fixed.",
    '',
    body.replace(/^## /gm, '### '),
  ].join('\n');
}

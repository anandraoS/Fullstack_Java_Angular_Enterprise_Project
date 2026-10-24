// Aggregates Maven surefire + failsafe XML reports into a per-module Markdown table.
// Usage: node scripts/test-summary.mjs [--md OUT.md]      (run after `mvn -f services/pom.xml verify`)
// Modules scanned: services/* and gateway. Counts come from the TEST-*.xml files on disk, not from console output.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mdIdx = process.argv.indexOf('--md');
const mdOut = mdIdx > 0 ? process.argv[mdIdx + 1] : null;

const modules = [];
for (const d of fs.readdirSync(path.join(ROOT, 'services'), { withFileTypes: true })) {
  if (d.isDirectory() && fs.existsSync(path.join(ROOT, 'services', d.name, 'pom.xml'))) modules.push(`services/${d.name}`);
}
if (fs.existsSync(path.join(ROOT, 'gateway/pom.xml'))) modules.push('gateway');

const attr = (tag, name) => {
  const m = new RegExp(`\\b${name}="([^"]*)"`).exec(tag);
  return m ? m[1] : null;
};

function scan(dir) {
  const r = { suites: 0, tests: 0, failures: 0, errors: 0, skipped: 0, time: 0, skippedSuites: [] };
  if (!fs.existsSync(dir)) return r;
  for (const f of fs.readdirSync(dir)) {
    if (!/^TEST-.*\.xml$/.test(f)) continue;
    const head = fs.readFileSync(path.join(dir, f), 'utf8').match(/<testsuite\b[^>]*>/)?.[0];
    if (!head) continue;
    const n = (k) => Number(attr(head, k) ?? 0);
    r.suites++;
    r.tests += n('tests');
    r.failures += n('failures');
    r.errors += n('errors');
    r.skipped += n('skipped');
    r.time += Number(attr(head, 'time') ?? 0);
    if (n('skipped') > 0) r.skippedSuites.push(`${(attr(head, 'name') ?? f).split('.').pop()} (${n('skipped')})`);
  }
  return r;
}

const rows = [];
const tot = { u: 0, uf: 0, i: 0, if: 0, skipped: 0, suites: 0, time: 0 };
const skippedAll = [];
for (const m of modules) {
  const u = scan(path.join(ROOT, m, 'target/surefire-reports'));
  const i = scan(path.join(ROOT, m, 'target/failsafe-reports'));
  const fail = u.failures + u.errors + i.failures + i.errors;
  rows.push({ m, u, i, fail });
  tot.u += u.tests; tot.uf += u.failures + u.errors; tot.i += i.tests; tot.if += i.failures + i.errors;
  tot.skipped += u.skipped + i.skipped; tot.suites += u.suites + i.suites; tot.time += u.time + i.time;
  for (const s of [...u.skippedSuites, ...i.skippedSuites]) skippedAll.push(`${m}: ${s}`);
}

const lines = [];
lines.push('| Module | Unit suites | Unit tests | IT suites | IT tests | Failures+Errors | Skipped | Time (s) |');
lines.push('|---|---:|---:|---:|---:|---:|---:|---:|');
for (const { m, u, i, fail } of rows) {
  lines.push(`| ${m} | ${u.suites} | ${u.tests} | ${i.suites} | ${i.tests} | ${fail} | ${u.skipped + i.skipped} | ${(u.time + i.time).toFixed(1)} |`);
}
lines.push(`| **TOTAL** | ${rows.reduce((a, r) => a + r.u.suites, 0)} | **${tot.u}** | ${rows.reduce((a, r) => a + r.i.suites, 0)} | **${tot.i}** | **${tot.uf + tot.if}** | **${tot.skipped}** | ${tot.time.toFixed(1)} |`);
lines.push('');
lines.push(`Executed (tests - skipped): ${tot.u + tot.i - tot.skipped}; passed: ${tot.u + tot.i - tot.skipped - tot.uf - tot.if}.`);
if (skippedAll.length) {
  lines.push('');
  lines.push('Skipped suites (name (count)):');
  for (const s of skippedAll) lines.push(`- ${s}`);
}
const out = lines.join('\n');
console.log(out);
if (mdOut) fs.writeFileSync(path.resolve(ROOT, mdOut), out + '\n');
process.exit(tot.uf + tot.if > 0 ? 1 : 0);

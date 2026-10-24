// Verifies that every metric referenced by observability/**/*.json (Grafana dashboards) and observability/**/*.yml
// (Prometheus alert/recording rules) exists in the REAL /actuator/prometheus output of the running stack.
// Usage: node scripts/check-metric-names.mjs [--no-scrape] [--ports 18080,18081,...]
//   default: scrapes http://localhost:{18080..18084}/actuator/prometheus, stores raw text in .run/metrics/<port>.prom
//   --no-scrape: reuse the files already in .run/metrics
// Metrics that no Spring service exposes (Prometheus' own `up`, kafka-exporter / postgres-exporter / node metrics, recorded
// series) are listed separately as EXTERNAL and are not asserted. Exit 1 when an assertable metric is missing.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const METRICS_DIR = path.join(ROOT, '.run/metrics');
const argv = process.argv.slice(2);
const ports = (argv.includes('--ports') ? argv[argv.indexOf('--ports') + 1] : '18080,18081,18082,18083,18084').split(',');

if (!argv.includes('--no-scrape')) {
  fs.mkdirSync(METRICS_DIR, { recursive: true });
  for (const p of ports) {
    try {
      const res = await fetch(`http://localhost:${p}/actuator/prometheus`, { signal: AbortSignal.timeout(15000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      fs.writeFileSync(path.join(METRICS_DIR, `${p}.prom`), await res.text());
    } catch (e) { console.error(`scrape :${p} failed: ${e.message}`); }
  }
}
const exposed = new Map(); // name -> Set(ports)
for (const f of fs.existsSync(METRICS_DIR) ? fs.readdirSync(METRICS_DIR) : []) {
  if (!f.endsWith('.prom')) continue;
  for (const line of fs.readFileSync(path.join(METRICS_DIR, f), 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const name = /^([a-zA-Z_:][a-zA-Z0-9_:]*)/.exec(line)?.[1];
    if (name) (exposed.get(name) ?? exposed.set(name, new Set()).get(name)).add(f.replace('.prom', ''));
  }
}
if (exposed.size === 0) { console.error('no scraped metrics found (is the stack running?)'); process.exit(2); }

// ---- collect expressions
const exprs = []; // {file, expr}
const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) => d.isDirectory() ? walk(path.join(dir, d.name)) : [path.join(dir, d.name)]);
const recorded = new Set();
for (const f of walk(path.join(ROOT, 'observability'))) {
  const rel = path.relative(ROOT, f).replace(/\\/g, '/');
  if (f.endsWith('.json')) {
    const visit = (o) => {
      if (Array.isArray(o)) o.forEach(visit);
      else if (o && typeof o === 'object') for (const [k, v] of Object.entries(o)) {
        if ((k === 'expr' || k === 'query') && typeof v === 'string') exprs.push({ file: rel, expr: v });
        else visit(v);
      }
    };
    visit(JSON.parse(fs.readFileSync(f, 'utf8')));
  } else if (/\.ya?ml$/.test(f) && rel.includes('rules/')) {
    const text = fs.readFileSync(f, 'utf8');
    for (const m of text.matchAll(/^\s*record:\s*(\S+)/gm)) recorded.add(m[1]);
    // expr: scalar OR block scalar (| / >) -- parse by indentation without a YAML library
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const m = /^(\s*)(?:- )?expr:\s*(.*)$/.exec(lines[i]);
      if (!m) continue;
      if (/^[|>][+-]?$/.test(m[2].trim())) {
        const indent = m[1].length; const buf = [];
        for (let j = i + 1; j < lines.length; j++) {
          if (lines[j].trim() === '' ) { buf.push(''); continue; }
          if (lines[j].search(/\S/) <= indent) break;
          buf.push(lines[j]);
        }
        exprs.push({ file: rel, expr: buf.join('\n') });
      } else exprs.push({ file: rel, expr: m[2].replace(/^["']|["']$/g, '') });
    }
  }
}

const KEYWORDS = new Set(['by', 'without', 'on', 'ignoring', 'group_left', 'group_right', 'offset', 'and', 'or', 'unless', 'bool', 'inf', 'nan']);
function metricsIn(expr) {
  let s = expr.replace(/"(?:[^"\\]|\\.)*"/g, '""').replace(/'(?:[^'\\]|\\.)*'/g, "''");
  s = s.replace(/\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/g, '');            // grafana variables
  s = s.replace(/\{[^{}]*\}/g, '');                                  // label matchers
  s = s.replace(/\[[^\]]*\]/g, '');                                  // ranges / subqueries
  s = s.replace(/\b(by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)/g, ' ');
  s = s.replace(/label_values\(\s*([a-zA-Z_:][\w:]*)\s*,[^)]*\)/g, ' $1 ');
  const out = new Set();
  for (const m of s.matchAll(/[a-zA-Z_:][a-zA-Z0-9_:]*/g)) {
    const id = m[0]; const after = s.slice(m.index + id.length).trimStart();
    if (KEYWORDS.has(id) || after.startsWith('(')) continue;        // function call or keyword
    if (/^\d/.test(id)) continue;
    out.add(id);
  }
  return out;
}

const EXTERNAL = [/^up$/, /^kafka_(consumergroup|topic|brokers)_/, /^pg_/, /^node_/, /^container_/, /^kube_/, /^ALERTS/, /^redis_/];
const refs = new Map(); // metric -> Set(files)
for (const { file, expr } of exprs) for (const id of metricsIn(expr)) (refs.get(id) ?? refs.set(id, new Set()).get(id)).add(file);

const ok = [], missing = [], external = [], rec = [];
for (const [id, files] of [...refs].sort()) {
  const hit = exposed.has(id);
  if (recorded.has(id)) rec.push(id);
  else if (hit) ok.push([id, [...exposed.get(id)].sort().join(',')]);
  else if (EXTERNAL.some((r) => r.test(id))) external.push([id, [...files].join(', ')]);
  else missing.push([id, [...files].join(', ')]);
}
console.log(`expressions scanned: ${exprs.length}; distinct metric names referenced: ${refs.size}; exposed series names scraped: ${exposed.size}`);
console.log(`\nFOUND in scraped output (${ok.length}):`); for (const [id, p] of ok) console.log(`  OK    ${id}   (ports ${p})`);
console.log(`\nEXTERNAL / infra metrics, not scrapeable from the services (${external.length}):`); for (const [id, f] of external) console.log(`  EXT   ${id}   [${f}]`);
if (rec.length) { console.log(`\nRecorded series defined by the rules themselves (${rec.length}): ${rec.join(', ')}`); }
console.log(`\nMISSING (${missing.length}):`); for (const [id, f] of missing) console.log(`  MISS  ${id}   [${f}]`);
process.exit(missing.length ? 1 : 0);

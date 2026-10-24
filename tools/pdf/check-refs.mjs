// Verifies that every repository path referenced in the PDF source documents exists. Exit code 1 on any missing path.
// Checked: inline-code tokens that start with a known top-level folder or file, and {{code:...}} includes,
// in docs/pdf, docs/traceability and docs/interview. Tokens with wildcards or placeholders (* < > { } $ ...) are skipped.
// Usage: node check-refs.mjs [extra markdown files or folders]
import fs from 'node:fs';
import path from 'node:path';
import { ROOT } from './lib/common.mjs';

const ROOTS = ['services/', 'gateway/', 'frontend/', 'infrastructure/', 'observability/', 'performance/', 'ai-engineering/',
  'docs/', 'scripts/', 'specs/', 'tests/', 'tools/', '.github/'];
const TOP_FILES = ['VERSION'];
const targets = ['docs/pdf', 'docs/traceability', 'docs/interview', ...process.argv.slice(2)];

function walk(p, out) {
  const abs = path.isAbsolute(p) ? p : path.join(ROOT, p);
  if (!fs.existsSync(abs)) return;
  if (fs.statSync(abs).isDirectory()) fs.readdirSync(abs).forEach((f) => walk(path.join(abs, f), out));
  else if (/\.(md|csv)$/.test(abs)) out.push(abs);
}

const files = [];
targets.forEach((t) => walk(t, files));
const missing = [];
let checked = 0;
const seen = new Set();

function check(token, file, line) {
  let t = token.replace(/[.,;:)]+$/, '').replace(/#.*$/, '').replace(/:\d+(-\d+)?$/, '');
  if (!ROOTS.some((r) => t.startsWith(r)) && !TOP_FILES.includes(t)) return;
  if (/[*<>{}$|]|\.\.\.|\\/.test(t)) return;
  if (!/\/[^/]+$/.test(t) && !t.endsWith('/')) return;
  if (/(^|\/)(coverage|target|dist|node_modules)(\/|$)/.test(t)) return; // generated build output, not shipped in the ZIP
  const key = t;
  if (seen.has(key)) return;
  seen.add(key);
  checked++;
  if (!fs.existsSync(path.join(ROOT, t))) missing.push(`${path.relative(ROOT, file)}:${line}  ${t}`);
}

for (const f of files) {
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  lines.forEach((ln, i) => {
    for (const m of ln.matchAll(/\{\{code:([^|}]+)\|/g)) check(m[1], f, i + 1);
    for (const m of ln.matchAll(/`([^`\s]+)`/g)) check(m[1], f, i + 1);
  });
}
console.log(`checked ${checked} distinct paths in ${files.length} files`);
if (missing.length) {
  console.log('MISSING PATHS:\n' + missing.join('\n'));
  process.exit(1);
}
console.log('all referenced paths exist');

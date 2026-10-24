// Renders every docs/diagrams/*.mmd to docs/diagrams/rendered/<name>.png (and .svg) in ONE mermaid-cli browser session.
// Needs mermaid-cli. Location: env MERMAID_HOME (a folder containing node_modules/.bin/mmdc[.cmd]) or <repo>/.work/tools/mermaid.
// One-time install (about 30 min on a slow machine): PUPPETEER_SKIP_DOWNLOAD=true npm install --prefix .work/tools/mermaid @mermaid-js/mermaid-cli
// The browser is Microsoft Edge/Chrome from docs/diagrams/puppeteer-config.json (edit executablePath for your machine).
// Rendered PNGs are committed, so build-pdf1/2 do NOT need mermaid-cli, only this script does.
// Usage: node render-diagrams.mjs [onlyPrefix]
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { ROOT, DOCS } from './lib/common.mjs';

const only = process.argv[2] ?? '';
const home = process.env.MERMAID_HOME ?? path.join(ROOT, '.work', 'tools', 'mermaid');
const mmdc = path.join(home, 'node_modules', '.bin', process.platform === 'win32' ? 'mmdc.cmd' : 'mmdc');
const cfg = path.join(DOCS, 'diagrams', 'puppeteer-config.json');
if (!fs.existsSync(mmdc)) { console.error('mmdc not found at', mmdc); process.exit(2); }

const dir = path.join(DOCS, 'diagrams');
const out = path.join(dir, 'rendered');
fs.mkdirSync(out, { recursive: true });
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.mmd') && f.startsWith(only)).sort();
const work = path.join(ROOT, '.work', 'diagram-batch');
fs.mkdirSync(work, { recursive: true });

for (const fmt of (process.env.SVG ? ['png', 'svg'] : ['png'])) {
  const md = files.map((f) => '```mermaid\n' + fs.readFileSync(path.join(dir, f), 'utf8').replace(/\r\n/g, '\n').trimEnd() + '\n```\n').join('\n');
  fs.writeFileSync(path.join(work, 'all.md'), md);
  const args = ['-p', cfg, '-i', path.join(work, 'all.md'), '-o', path.join(work, `all-${fmt}.md`), '-e', fmt, '-b', 'white', '-j', '2'];
  if (fmt === 'png') args.push('--size', '1800', '-s', '2');
  console.log('rendering', files.length, 'diagrams as', fmt);
  try {
    execFileSync(mmdc, args, { stdio: 'inherit', shell: true, timeout: 900000 });
  } catch (e) { console.error('mermaid-cli reported an error for', fmt); }
  files.forEach((f, i) => {
    const produced = path.join(work, `all-${fmt}-${i + 1}.${fmt}`);
    if (fs.existsSync(produced)) fs.copyFileSync(produced, path.join(out, f.replace(/\.mmd$/, `.${fmt}`)));
    else console.error('FAILED to render', f, fmt);
  });
}

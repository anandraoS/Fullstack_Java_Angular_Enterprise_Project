// Serves a BUILT front end and proxies /api to the gateway: the same shape as the nginx image (same-origin UI + /api), without
// Docker and without a dev server. Used for the live Playwright runs.
//   node scripts/serve-ui.mjs        env: UI (angular|react, default angular), PORT (4200 angular / 4201 react),
//                                         GATEWAY_URL (http://localhost:18080), UI_DIR (override the dist folder)
// Build first if dist is missing:  cd frontend/oms-ui && npx ng build   |   cd frontend/oms-ui-react && npm run build
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const UI = (process.env.UI ?? 'angular').toLowerCase();
if (UI !== 'angular' && UI !== 'react') {
  console.error(`UI must be 'angular' or 'react' (got '${UI}')`);
  process.exit(2);
}
const DEFAULT_DIR = UI === 'react' ? 'frontend/oms-ui-react/dist' : 'frontend/oms-ui/dist/oms-ui/browser';
const UI_DIR = path.resolve(process.env.UI_DIR ?? path.join(ROOT, DEFAULT_DIR));
const PORT = Number(process.env.PORT ?? (UI === 'react' ? 4201 : 4200));
const GW = new URL(process.env.GATEWAY_URL ?? 'http://localhost:18080');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };

http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const up = http.request({ host: GW.hostname, port: GW.port, path: req.url, method: req.method, headers: { ...req.headers, host: GW.host } }, (r) => {
      res.writeHead(r.statusCode ?? 502, r.headers);
      r.pipe(res);
    });
    up.on('error', () => { res.writeHead(502, { 'Content-Type': 'application/problem+json' }); res.end('{"code":"UPSTREAM_UNAVAILABLE","status":502}'); });
    req.pipe(up);
    return;
  }
  const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  let file = path.normalize(path.join(UI_DIR, rel));
  if (!file.startsWith(UI_DIR)) { res.writeHead(403); return res.end(); }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(UI_DIR, 'index.html'); // SPA fallback
  if (!fs.existsSync(file)) { res.writeHead(404, { 'Content-Type': 'text/plain' }); return res.end(`UI not built: ${UI_DIR}`); }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-cache' });
  fs.createReadStream(file).pipe(res);
}).listen(PORT, () => console.log(`OMS UI (${UI}) on http://localhost:${PORT} (dist ${UI_DIR}) -> /api proxied to ${GW.origin}`));

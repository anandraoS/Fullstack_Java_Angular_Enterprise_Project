// Usage: node scripts/lib/wait-for.mjs tcp host:port [timeoutSeconds]
//        node scripts/lib/wait-for.mjs http URL [timeoutSeconds]    (waits for HTTP 200 whose body contains "UP" if JSON status present)
// Exit 0 when ready, 1 on timeout.
import http from 'node:http';
import net from 'node:net';

const [kind, target, secs = '120'] = process.argv.slice(2);
const deadline = Date.now() + Number(secs) * 1000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function tryTcp() {
  const [host, port] = target.split(':');
  return new Promise((resolve) => {
    const s = net.connect({ host, port: Number(port), timeout: 1500 }, () => { s.destroy(); resolve(true); });
    s.on('error', () => resolve(false));
    s.on('timeout', () => { s.destroy(); resolve(false); });
  });
}
// node:http instead of global fetch: on Windows + Node 24, process.exit() right after a fetch() with AbortSignal.timeout() crashed
// with "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING) ... src\win\async.c" (exit code 134), which made run-local.sh
// report a healthy service as "not ready" (integration defect, see docs/validation/integration-defects.md).
function tryHttp() {
  return new Promise((resolve) => {
    const req = http.get(target, { timeout: 3000 }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve(res.statusCode === 200 && (!body.includes('"status"') || body.includes('"UP"'))));
    });
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.on('error', () => resolve(false));
  });
}

if (!['tcp', 'http'].includes(kind) || !target) {
  console.error('usage: wait-for.mjs tcp|http target [seconds]');
  process.exit(2);
}
let ready = false;
while (Date.now() < deadline) {
  if (await (kind === 'tcp' ? tryTcp() : tryHttp())) { ready = true; break; }
  await sleep(1500);
}
process.exitCode = ready ? 0 : 1;

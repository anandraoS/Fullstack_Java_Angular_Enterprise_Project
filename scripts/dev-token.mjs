// Fetches a dev JWT from the gateway dev token endpoint (profile `dev` only) and prints ONLY the access token to stdout.
// Usage: OMS_DEMO_PASSWORD=... node scripts/dev-token.mjs [username] [--base http://localhost:18080] [--json]
//   export TOKEN=$(OMS_DEMO_PASSWORD=... node scripts/dev-token.mjs operator1)
// The password comes from the environment, never from argv (so it does not appear in process listings or shell history).
const args = process.argv.slice(2);
const flag = (n) => { const i = args.indexOf(n); if (i < 0) return undefined; const v = args[i + 1]; args.splice(i, 2); return v; };
const base = (flag('--base') ?? process.env.OMS_BASE_URL ?? 'http://localhost:18080').replace(/\/$/, '');
const asJson = args.includes('--json');
const username = args.find((a) => !a.startsWith('--')) ?? 'customer1';
const password = process.env.OMS_DEMO_PASSWORD;
if (!password) {
  console.error('OMS_DEMO_PASSWORD is not set.');
  process.exit(2);
}
const res = await fetch(`${base}/api/v1/auth/token`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username, password }),
  signal: AbortSignal.timeout(10000),
});
const body = await res.json().catch(() => ({}));
if (!res.ok || !body.accessToken) {
  console.error(`token request failed: HTTP ${res.status} ${body.code ?? ''}`);
  process.exit(1);
}
console.log(asJson ? JSON.stringify(body, null, 2) : body.accessToken);

// Downloads validation tools (kubectl for `kubectl kustomize`, kubeconform, terraform, gitleaks) into
// .work/tools/<tool>/ and verifies each against the publisher's published SHA-256 BEFORE it is extracted or used.
// Windows x64 by default; set OMS_TOOLS_PLATFORM=linux-amd64 on Linux. Usage: node scripts/fetch-validation-tools.mjs [tool...]
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TOOLS = path.join(ROOT, '.work', 'tools');
const PLATFORM = process.env.OMS_TOOLS_PLATFORM ?? 'windows-amd64';
const WIN = PLATFORM.startsWith('windows');
const [OS, ARCH] = PLATFORM.split('-');
const V = { kustomize: '5.5.0', kubectl: '1.31.4', kubeconform: '0.6.7', terraform: '1.9.8', gitleaks: '8.21.2' };

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
async function get(url, attempts = 4) {
  let last;
  for (let i = 0; i < attempts; i++) {
    try {
      const r = await fetch(url, { redirect: 'follow' });
      if (!r.ok) throw new Error(`${r.status} ${url}`);
      return Buffer.from(await r.arrayBuffer());
    } catch (e) {
      last = e;
    }
  }
  throw last;
}
const firstHex = (txt) => /([a-f0-9]{64})/i.exec(txt)?.[1]?.toLowerCase();
const lineHex = (txt, needle) => firstHex(txt.split('\n').find((l) => l.includes(needle)) ?? '');
function extract(archive, dest) {
  // python -I (isolated) / tar: extraction only reads the verified archive.
  if (archive.endsWith('.zip')) {
    execFileSync('python', ['-I', '-c', 'import sys,zipfile;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])', archive, dest]);
  } else {
    execFileSync('tar', ['-xzf', archive, '-C', dest]);
  }
}
const ext = WIN ? 'zip' : 'tar.gz';
const exe = WIN ? '.exe' : '';

const jobs = {
  // Standalone kustomize (small download). kubectl above also embeds kustomize; either is accepted by validate-infra.sh.
  async kustomize(dir) {
    const rel = `https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv${V.kustomize}`;
    const name = `kustomize_v${V.kustomize}_${OS}_${ARCH}.${ext}`;
    const archive = await get(`${rel}/${name}`);
    const sums = (await get(`${rel}/checksums.txt`)).toString();
    if (sha256(archive) !== lineHex(sums, name)) throw new Error('checksum mismatch');
    const f = path.join(dir, name);
    fs.writeFileSync(f, archive);
    extract(f, dir);
  },
  async kubectl(dir) {
    const base = `https://dl.k8s.io/release/v${V.kubectl}/bin/${OS}/${ARCH}/kubectl${exe}`;
    const bin = await get(base);
    const expected = firstHex((await get(`${base}.sha256`)).toString());
    if (sha256(bin) !== expected) throw new Error('checksum mismatch');
    fs.writeFileSync(path.join(dir, `kubectl${exe}`), bin, { mode: 0o755 });
  },
  async kubeconform(dir) {
    const rel = `https://github.com/yannh/kubeconform/releases/download/v${V.kubeconform}`;
    const name = `kubeconform-${OS}-${ARCH}.${ext}`;
    const archive = await get(`${rel}/${name}`);
    const sums = (await get(`${rel}/CHECKSUMS`)).toString();
    if (sha256(archive) !== lineHex(sums, name)) throw new Error('checksum mismatch');
    const f = path.join(dir, name);
    fs.writeFileSync(f, archive);
    extract(f, dir);
  },
  async terraform(dir) {
    const rel = `https://releases.hashicorp.com/terraform/${V.terraform}`;
    const name = `terraform_${V.terraform}_${OS}_${ARCH}.zip`;
    const archive = await get(`${rel}/${name}`);
    const sums = (await get(`${rel}/terraform_${V.terraform}_SHA256SUMS`)).toString();
    if (sha256(archive) !== lineHex(sums, name)) throw new Error('checksum mismatch');
    const f = path.join(dir, name);
    fs.writeFileSync(f, archive);
    extract(f, dir);
  },
  async gitleaks(dir) {
    const v = V.gitleaks;
    const rel = `https://github.com/gitleaks/gitleaks/releases/download/v${v}`;
    const name = `gitleaks_${v}_${OS}_${ARCH === 'amd64' ? 'x64' : ARCH}.${ext}`;
    const archive = await get(`${rel}/${name}`);
    const sums = (await get(`${rel}/gitleaks_${v}_checksums.txt`)).toString();
    if (sha256(archive) !== lineHex(sums, name)) throw new Error('checksum mismatch');
    const f = path.join(dir, name);
    fs.writeFileSync(f, archive);
    extract(f, dir);
  },
};

const wanted = process.argv.slice(2).length ? process.argv.slice(2) : ['kustomize', 'kubeconform', 'terraform', 'gitleaks'];
const results = [];
for (const name of wanted) {
  const dir = path.join(TOOLS, name);
  fs.mkdirSync(dir, { recursive: true });
  try {
    if (!jobs[name]) throw new Error('unknown tool');
    await jobs[name](dir);
    results.push(`${name}: OK (checksum verified)`);
  } catch (e) {
    results.push(`${name}: FAILED (${e.message})`);
  }
}
console.log(results.join('\n'));

// node scripts/trim-event-samples.mjs <full.jsonl> <sample.jsonl> [perType=2]: keeps the first N captured records of each eventType.
import fs from 'node:fs';
const [src, dst, n = '2'] = process.argv.slice(2);
const seen = {};
const out = [];
for (const line of fs.readFileSync(src, 'utf8').split(/\r?\n/).filter(Boolean)) {
  const type = JSON.parse(line).value?.eventType ?? 'unknown';
  seen[type] = (seen[type] ?? 0) + 1;
  if (seen[type] <= Number(n)) out.push(line);
}
fs.writeFileSync(dst, out.join('\n') + '\n');
console.log(`trimmed ${out.length} sample record(s) (${Object.keys(seen).length} event types) -> ${dst}`);

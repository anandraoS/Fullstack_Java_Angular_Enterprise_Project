// Validates captured OMS events (JSON Lines) against specs/events/*.schema.json.
// Usage: node scripts/validate-events.mjs [samples.jsonl ...]   (default: tests/e2e/samples/kafka-events.sample.jsonl)
// Each line is either a bare event envelope or the capture wrapper {"topic","partition","offset","key","value":<envelope>}
// produced by scripts/capture-events.sh (a Java Kafka consumer reading the live embedded Kafka from earliest).
// Exit code 1 if any event is invalid or has no schema. Also reports which of the 15 event types were actually observed.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validate } from './lib/mini-jsonschema.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SCHEMAS = path.join(ROOT, 'specs/events');
const files = process.argv.slice(2).length ? process.argv.slice(2) : [path.join(ROOT, 'tests/e2e/samples/kafka-events.sample.jsonl')];

const schemas = {};
for (const f of fs.readdirSync(SCHEMAS)) {
  if (f.endsWith('.schema.json')) schemas[f.replace('.schema.json', '')] = JSON.parse(fs.readFileSync(path.join(SCHEMAS, f), 'utf8'));
}
const known = Object.keys(schemas).filter((k) => k !== 'envelope');

let total = 0, bad = 0, noSchema = 0, dlt = 0;
const seen = {}, perTopic = {};
for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean);
  lines.forEach((line, i) => {
    let rec;
    try { rec = JSON.parse(line); } catch (e) { bad++; total++; console.log(`INVALID ${file}:${i + 1} not JSON (${e.message})`); return; }
    const topic = rec.topic ?? '(bare)';
    if (topic.endsWith('.DLT')) { dlt++; }
    const ev = rec.value !== undefined ? (typeof rec.value === 'string' ? safeParse(rec.value) : rec.value) : rec;
    total++;
    perTopic[topic] = (perTopic[topic] ?? 0) + 1;
    if (!ev || typeof ev !== 'object') { bad++; console.log(`INVALID ${file}:${i + 1} value is not an envelope object`); return; }
    const envErrs = validate(schemas.envelope, ev);
    const s = schemas[ev.eventType];
    if (!s) { noSchema++; console.log(`NO-SCHEMA ${file}:${i + 1} eventType=${ev.eventType}`); return; }
    const errs = [...envErrs, ...validate(s, ev)];
    seen[ev.eventType] = (seen[ev.eventType] ?? 0) + 1;
    if (errs.length) { bad++; console.log(`INVALID ${file}:${i + 1} ${ev.eventType} eventId=${ev.eventId}\n  - ${[...new Set(errs)].join('\n  - ')}`); }
  });
}
function safeParse(s) { try { return JSON.parse(s); } catch { return null; } }

console.log(`\nvalidated ${total} record(s) from ${files.length} file(s): ${total - bad - noSchema} valid, ${bad} invalid, ${noSchema} without schema, ${dlt} from DLT topics`);
console.log('per topic:', JSON.stringify(perTopic));
console.log('event types seen:');
for (const k of known) console.log(`  ${seen[k] ? 'OBSERVED' : 'not observed'}  ${k}${seen[k] ? ' x' + seen[k] : ''}`);
process.exit(bad + noSchema > 0 ? 1 : 0);

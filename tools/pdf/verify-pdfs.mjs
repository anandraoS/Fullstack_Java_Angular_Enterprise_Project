// Verifies both generated PDFs: size, page count, required sections, footer on every page, page numbers, bookmarks,
// fonts, rendering artefacts, blank pages, text overflow (text positioned outside the page), images, and PDF 2 question counts.
// Usage: node verify-pdfs.mjs
import fs from 'node:fs';
import path from 'node:path';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import { DOCS, FOOTER_LINES } from './lib/common.mjs';

const PDF1 = path.join(DOCS, 'pdf', 'Fullstack_Java_Angular_Platform_Architecture.pdf');
const PDF2 = path.join(DOCS, 'pdf', 'Fullstack_Java_Angular_Deep_Dive_Interview_Guide.pdf');
const PDF1_SECTIONS = [
  '1. Business problem and requirements', '2. System context and component diagrams', '3. Service boundaries', '4. Frontend architecture: Angular and React',
  '5. REST API design', '6. Database ER diagram', '7. Order lifecycle and state machine', '8. Kafka event flows',
  '9. Saga orchestration and compensation', '10. Inventory concurrency handling', '11. Security architecture',
  '12. Docker and Kubernetes deployment', '13. CI/CD pipeline', '14. Observability architecture', '15. Failure and recovery flows',
  '16. Testing strategy', '17. AI-assisted development workflow', '18. Architecture Decision Records', '19. Architecture mind map',
  '20. Troubleshooting scenarios', '21. Limitations and future enhancements', 'Appendix A. Job-description traceability matrix', 'Appendix B. Integration defects found and fixed',
];
const PDF2_SECTIONS = [
  'How to use this guide', 'Chapter 01.', 'Chapter 02.', 'Chapter 03.', 'Chapter 04.', 'Chapter 05.', 'Chapter 06.', 'Chapter 07.',
  'Chapter 08.', 'Chapter 09.', 'Chapter 10.', 'Chapter 11.', 'Chapter 12.', 'Chapter 12b.', 'Chapter 13.', 'Chapter 14.', 'Chapter 15.',
  'Chapter 16.', 'Chapter 17.', 'Interview Q&A, part 1', 'Interview Q&A, part 2', 'Interview Q&A, part 3', 'Interview Q&A, part 4', 'Interview Q&A, part 5',
  'Appendix. Job-description traceability matrix',
];

let failures = 0;
const out = [];
const rec = (ok, msg) => { out.push(`${ok ? 'PASS' : 'FAIL'}  ${msg}`); if (!ok) failures++; };

async function analyse(file, label, sections, opts) {
  out.push(`\n== ${label}: ${path.basename(file)}`);
  if (!fs.existsSync(file)) { rec(false, 'file exists'); return null; }
  const buf = fs.readFileSync(file);
  rec(buf.length > 200000, `size ${(buf.length / 1024 / 1024).toFixed(2)} MB`);
  const pdf = await pdfjs.getDocument({ data: new Uint8Array(buf), disableFontFace: true, useSystemFonts: false, verbosity: 0 }).promise;
  const n = pdf.numPages;
  rec(n >= opts.minPages, `page count ${n} (minimum ${opts.minPages})`);
  const outline = await pdf.getOutline();
  rec(Array.isArray(outline) && outline.length >= 15, `bookmarks present: ${outline ? outline.length : 0} top-level entries`);
  const texts = [];
  const fonts = new Set();
  let images = 0, landscape = 0, overflow = [];
  for (let p = 1; p <= n; p++) {
    const page = await pdf.getPage(p);
    const vp = page.getViewport({ scale: 1 });
    if (vp.width > vp.height) landscape++;
    const tc = await page.getTextContent();
    texts.push(tc.items.map((i) => i.str).join(' '));
    for (const it of tc.items) {
      const x = it.transform[4], y = it.transform[5];
      if (it.str.trim() && (x < -1 || x > vp.width + 1 || y < -1 || y > vp.height + 1)) { overflow.push(p); break; }
      if (it.fontName) fonts.add(it.fontName);
    }
    const ops = await page.getOperatorList();
    for (const fn of ops.fnArray) if (fn === pdfjs.OPS.paintImageXObject || fn === pdfjs.OPS.paintInlineImageXObject) images++;
  }
  const all = texts.join('\n');
  const flat = (s) => s.replace(/\s+/g, ' ');
  const allFlat = flat(all);
  for (const s of sections) rec(allFlat.includes(flat(s)), `section present: "${s}"`);
  const missing = [];
  for (let p = 1; p <= n; p++) if (!FOOTER_LINES.every((l) => flat(texts[p - 1]).includes(flat(l)))) missing.push(p);
  rec(missing.length === 0, `two-line Cotiviti footer on every page including the cover${missing.length ? ' (missing ' + missing.slice(0, 10).join(',') + ')' : ''}`);
  rec(texts.slice(1).every((t, i) => t.includes(`Page ${i + 2} of ${n}`)), 'page numbers "Page i of N" on every page after the cover');
  for (const bad of [/\bundefined undefined\b/, /\[object Object/, /missing image/i, /diagram [\w-]+ not available/i, /\{\{(code|diagram):/, /NaN%/]) {
    const hit = texts.findIndex((t) => bad.test(t));
    rec(hit < 0, `no rendering artefact ${bad}${hit >= 0 ? ' (page ' + (hit + 1) + ')' : ''}`);
  }
  rec(overflow.length === 0, `no text positioned outside the page${overflow.length ? ' (pages ' + overflow.slice(0, 8).join(',') + ')' : ''}`);
  rec(images >= opts.minImages, `embedded images ${images} (minimum ${opts.minImages})`);
  const empty = texts.map((t, i) => [i + 1, t.replace(/Page \d+ of \d+/, '').trim().length]).filter(([, l]) => l < 300).map(([p]) => p);
  // the footer itself is ~230 chars, so a page with < 300 chars has essentially no body text
  rec(empty.length <= opts.maxNearEmpty, `near-empty pages ${empty.length} (${empty.slice(0, 15).join(',')}); allowed ${opts.maxNearEmpty} (diagram pages carry little text)`);
  out.push(`INFO  fonts used: ${[...fonts].length}; landscape pages: ${landscape}`);
  const meta = await pdf.getMetadata();
  out.push(`INFO  title: ${meta.info?.Title}`);
  return { all, n, texts };
}

const r1 = await analyse(PDF1, 'PDF 1', PDF1_SECTIONS, { minPages: 100, minImages: 20, maxNearEmpty: 30 });
const r2 = await analyse(PDF2, 'PDF 2', PDF2_SECTIONS, { minPages: 200, minImages: 0, maxNearEmpty: 10 });
if (r2) {
  const qs = new Set([...r2.all.matchAll(/\bQ(\d{1,3})\.\s/g)].map((m) => Number(m[1])));
  rec(qs.size >= 160, `distinct question headings Q#. found: ${qs.size} (minimum 160)`);
  const scen = (r2.all.match(/Type:\s*Scenario/g) ?? []).length;
  rec(scen >= 53, `scenario-type questions: ${scen} (minimum 53)`);
  for (const c of ['high-traffic order platform', 'overselling', 'saga across order and inventory', 'duplicate Kafka events', 'failed Kafka consumer',
    'slow REST', 'slow PostgreSQL query', 'service-to-service', 'Deploy Spring Boot microservices', 'concurrent reservations', 'consumer lag', 'AI-generated code']) {
    rec(new RegExp(c, 'i').test(r2.all), `mandatory prompt covered: ${c}`);
  }
  rec(/React/.test(r2.all) && /Redux/.test(r2.all) && /RTK Query/.test(r2.all), 'React/Redux/RTK Query chapter and questions present');
  rec(/IdempotencyKeyHolder/.test(r2.all) && /createListenerMiddleware/.test(r2.all) && !/there is no React in this repository/i.test(r2.all), 'chapter 12b is implementation-backed (real React code included, no stale no-React claim)');
  rec(/analog/i.test(r2.all) && /Beginner tier/.test(r2.all) && /Intermediate tier/.test(r2.all) && /Advanced tier/.test(r2.all), 'analogies and three tiers present');
}
console.log(out.join('\n'));
console.log(`\n${failures === 0 ? 'ALL PDF CHECKS PASSED' : failures + ' PDF CHECK(S) FAILED'}`);
process.exit(failures ? 1 : 0);

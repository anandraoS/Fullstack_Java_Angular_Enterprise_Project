// Builds PDF 2: Fullstack_Java_Angular_Deep_Dive_Interview_Guide.pdf from docs/interview/*.md.
// Chapter files NN-*.md become chapters (title from the H1); questions-N.md files become the Q&A parts.
import fs from 'node:fs';
import path from 'node:path';
import { PdfBuilder } from './lib/pdf-engine.mjs';
import { DOCS, FOOTER_LINES, read, dropTitle, stripLocalLinks, join } from './lib/common.mjs';
import { matrixCards } from './lib/matrix.mjs';
import { expandIncludes } from './lib/expand.mjs';

const OUT = path.join(DOCS, 'pdf', 'Fullstack_Java_Angular_Deep_Dive_Interview_Guide.pdf');
const dir = path.join(DOCS, 'interview');
const files = fs.readdirSync(dir).filter((f) => /^\d\d[a-z]?-.*\.md$/.test(f)).sort();
const qfiles = fs.readdirSync(dir).filter((f) => /^questions-\d+\.md$/.test(f)).sort();

const sections = [{ title: 'How to use this guide', md: read('docs/pdf/pdf2-intro.md') }];
for (const f of files) {
  const md = stripLocalLinks(read(`docs/interview/${f}`));
  const h1 = /^#\s+(.*)$/m.exec(md)?.[1] ?? f;
  const num = /^(\d\d[a-z]?)-/.exec(f)[1];
  sections.push({ title: `Chapter ${num}. ${h1.replace(/^Chapter\s+\d+[a-z]?[.:]\s*/i, '')}`, md: dropTitle(md) });
}
qfiles.forEach((f, i) => {
  const md = stripLocalLinks(read(`docs/interview/${f}`));
  const range = /Q\d+\s*-\s*Q?\d+/.exec(md.split('\n')[0])?.[0] ?? '';
  sections.push({ title: `Interview Q&A, part ${i + 1} (${range.replace(/\s/g, '')})`, md: dropTitle(md) });
});
sections.push({
  title: 'Appendix. Job-description traceability matrix',
  md: matrixCards(),
});
sections.forEach((s) => { s.md = expandIncludes(s.md, s.title); s.baseDir = DOCS; });

const b = new PdfBuilder({
  outFile: OUT,
  title: 'Fullstack Java and Angular Deep-Dive Interview Guide',
  subtitle: 'Deep reference chapters with analogies and codebase examples, plus 164 interview questions with model answers',
  coverLines: [
    'Document: PDF 2 of 2, Deep-dive reference and interview guide',
    'Covers: Java 17 and JVM, Spring Boot and Cloud, REST, microservices, Kafka, saga, PostgreSQL, Angular and TypeScript, RxJS and Signals, Spring Security and OAuth, Docker and Kubernetes, cloud, CI/CD, testing, observability, AI-assisted development, technical leadership, React, Redux Toolkit and RTK Query (implemented) mapped to Angular',
    'Companion: Fullstack_Java_Angular_Platform_Architecture.pdf',
    `Built: ${new Date().toISOString().slice(0, 10)}.`,
  ],
  footerLines: FOOTER_LINES,
  tocLevels: 2,
});
b.build(sections);
const pages = b.doc.bufferedPageRange().count;
await b.save();
console.log(JSON.stringify({ out: OUT, pages, stats: b.stats }, null, 2));

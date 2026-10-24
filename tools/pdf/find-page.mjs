// Prints the page numbers whose text contains a phrase. Usage: node find-page.mjs <file.pdf> "<phrase>"
import fs from 'node:fs';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';

const [file, phrase] = process.argv.slice(2);
const pdf = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(file)), verbosity: 0 }).promise;
const hits = [];
for (let p = 1; p <= pdf.numPages; p++) {
  const tc = await (await pdf.getPage(p)).getTextContent();
  if (tc.items.map((i) => i.str).join(' ').includes(phrase)) hits.push(p);
}
console.log(hits.join(','));

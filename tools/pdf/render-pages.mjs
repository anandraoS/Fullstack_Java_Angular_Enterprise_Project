// Rasterises selected PDF pages to PNG (for visual inspection).
// Usage: node render-pages.mjs <file.pdf> <outDir> <page,page,...> [scale]
import fs from 'node:fs';
import path from 'node:path';
import { createCanvas } from '@napi-rs/canvas';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';

const [file, outDir, pagesArg, scaleArg] = process.argv.slice(2);
const scale = Number(scaleArg ?? 1.3);
const data = new Uint8Array(fs.readFileSync(file));
const pdf = await pdfjs.getDocument({ data, useSystemFonts: false, disableFontFace: true }).promise;
fs.mkdirSync(outDir, { recursive: true });
const pages = pagesArg.split(',').map(Number);
for (const n of pages) {
  if (n < 1 || n > pdf.numPages) continue;
  const page = await pdf.getPage(n);
  const vp = page.getViewport({ scale });
  const canvas = createCanvas(Math.ceil(vp.width), Math.ceil(vp.height));
  const ctx = canvas.getContext('2d');
  await page.render({ canvasContext: ctx, viewport: vp, canvas }).promise;
  const out = path.join(outDir, `page-${String(n).padStart(3, '0')}.png`);
  fs.writeFileSync(out, canvas.toBuffer('image/png'));
  console.log(out);
}
console.log('total pages', pdf.numPages);

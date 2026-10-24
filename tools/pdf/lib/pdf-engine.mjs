// Markdown -> PDF engine built on pdfkit. Supports headings, paragraphs with inline styles, lists,
// tables (with repeated header rows), code blocks, block quotes, images with captions (auto landscape
// pages for wide diagrams), Mermaid blocks (rendered through an injected callback), a clickable table
// of contents with leader dots, PDF bookmarks, and a footer with page numbers on every page.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import PDFDocument from 'pdfkit';
import { marked } from 'marked';

const require = createRequire(import.meta.url);
const FONT_DIR = path.join(path.dirname(require.resolve('dejavu-fonts-ttf/package.json')), 'ttf');

export const COLORS = {
  ink: '#1c2430',
  muted: '#5b6573',
  navy: '#17324d',
  accent: '#0f766e',
  rule: '#c9d1da',
  codeBg: '#f2f4f7',
  codeInk: '#1f2937',
  tableHead: '#17324d',
  tableAlt: '#f6f8fa',
  link: '#0b5cad',
  quote: '#e8f3f1',
  warn: '#fff4e5',
};

const PAGE = { w: 595.28, h: 841.89 };
const M = { left: 54, right: 54, top: 58, bottom: 78 };
const BODY = 9.6;

function decode(s) {
  return String(s ?? '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}

/** Adds zero-width break opportunities inside very long unbroken words (paths, signatures, JSON). */
function softBreak(s) {
  // Commas followed by a letter are not line-break opportunities, so long comma-separated
  // signatures and JSON snippets get a space after each comma (cosmetic, enables wrapping).
  return String(s).replace(/\S{16,}/g, (w) => w.replace(/,(?=[^\s,])/g, ', '));
}

function slug(s) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60);
}

export class PdfBuilder {
  /**
   * @param {object} o
   * @param {string} o.outFile
   * @param {string} o.title
   * @param {string} o.subtitle
   * @param {string[]} o.coverLines extra lines on the cover
   * @param {string[]} o.footerLines the (two) mandatory footer lines
   * @param {(code:string)=>string|null} [o.renderMermaid]
   * @param {number} [o.tocLevels]
   */
  constructor(o) {
    this.o = { tocLevels: 2, ...o };
    this.doc = new PDFDocument({
      size: 'A4',
      margins: { ...M },
      bufferPages: true,
      autoFirstPage: false,
      info: { Title: o.title, Subject: o.subtitle, Author: 'OMS platform documentation generator', Creator: 'pdfkit' },
    });
    const d = this.doc;
    d.registerFont('Sans', path.join(FONT_DIR, 'DejaVuSans.ttf'));
    d.registerFont('Sans-Bold', path.join(FONT_DIR, 'DejaVuSans-Bold.ttf'));
    d.registerFont('Sans-Italic', path.join(FONT_DIR, 'DejaVuSans-Oblique.ttf'));
    d.registerFont('Sans-BoldItalic', path.join(FONT_DIR, 'DejaVuSans-BoldOblique.ttf'));
    d.registerFont('Mono', path.join(FONT_DIR, 'DejaVuSansMono.ttf'));
    d.registerFont('Mono-Bold', path.join(FONT_DIR, 'DejaVuSansMono-Bold.ttf'));
    this.headings = [];
    this.figureNo = 0;
    this.outlineStack = [];
    this.stats = { images: 0, tables: 0, codeBlocks: 0, mermaid: 0, mermaidFailed: 0, missingImages: [] };
    this.destSeq = 0;
  }

  // ---------- geometry helpers ----------
  get left() { return this.doc.page.margins.left; }
  get width() { return this.doc.page.width - this.doc.page.margins.left - this.doc.page.margins.right; }
  get bottomLimit() { return this.doc.page.height - this.doc.page.margins.bottom; }
  newPage(opts = {}) { this.doc.addPage({ size: 'A4', margins: { ...M }, ...opts }); }
  ensure(h) { if (this.doc.y + h > this.bottomLimit) this.newPage(); }
  currentPageNo() { const r = this.doc.bufferedPageRange(); return r.start + r.count; }

  // ---------- public API ----------
  /** sections: [{ title, md, baseDir, level? }] — each becomes a level-1 chapter on a new page. */
  build(sections) {
    const d = this.doc;
    this.cover();
    // Reserve TOC pages: count headings first.
    const preHeadings = this.countHeadings(sections);
    const perPage = 38;
    this.tocPages = Math.max(1, Math.ceil((preHeadings + 1) / perPage));
    this.tocStartIndex = d.bufferedPageRange().count;
    for (let i = 0; i < this.tocPages; i++) this.newPage();

    for (const s of sections) {
      this.afterFigure = false;
      this.reuseFigurePage = false;
      const md = s.md ?? '';
      const tokens = marked.lexer(md);
      // Drop a leading H1 that repeats the chapter title.
      if (tokens[0]?.type === 'heading' && tokens[0].depth === 1) tokens.shift();
      // A chapter that is just a short intro and one wide figure gets a landscape chapter page.
      const lead = tokens.filter((t) => t.type !== 'space').slice(0, 2);
      const figTok = lead.find((t) => t.type === 'paragraph' && t.tokens?.length === 1 && t.tokens[0].type === 'image');
      const wideFirst =
        figTok && lead.indexOf(figTok) === lead.length - 1 &&
        this.isWide(figTok.tokens[0], { baseDir: s.baseDir ?? process.cwd() }) &&
        this.figureModeOf(figTok.tokens[0], { baseDir: s.baseDir ?? process.cwd() }) === 'landscape';
      if (wideFirst) {
        this.newPage({ layout: 'landscape', margins: { left: 36, right: 36, top: 40, bottom: 70 } });
        this.reuseFigurePage = true;
      } else {
        this.newPage();
      }
      this.chapterHeading(s.title);
      this.blocks(tokens, { left: this.left, width: this.width, baseDir: s.baseDir ?? process.cwd() });
    }
    this.writeToc(perPage);
    this.stampFooters();
    return d;
  }

  save() {
    return new Promise((resolve, reject) => {
      fs.mkdirSync(path.dirname(this.o.outFile), { recursive: true });
      const out = fs.createWriteStream(this.o.outFile);
      out.on('finish', resolve);
      out.on('error', reject);
      this.doc.pipe(out);
      this.doc.end();
    });
  }

  // ---------- structure ----------
  inToc(level, text) {
    if (level < 2) return false;
    if (this.o.tocInclude) return this.o.tocInclude(level, text);
    return level <= this.o.tocLevels;
  }

  countHeadings(sections) {
    let n = 0;
    for (const s of sections) {
      n += 1; // chapter
      for (const t of marked.lexer(s.md ?? '')) {
        if (t.type === 'heading' && this.inToc(t.depth === 1 ? 2 : t.depth, decode(t.text))) n += 1;
      }
    }
    return n;
  }

  cover() {
    const d = this.doc;
    this.newPage();
    d.rect(0, 0, PAGE.w, 250).fill(COLORS.navy);
    d.rect(0, 250, PAGE.w, 6).fill(COLORS.accent);
    d.fillColor('#ffffff').font('Sans-Bold').fontSize(27).text(this.o.title, 54, 90, { width: PAGE.w - 108, lineGap: 4 });
    d.moveDown(0.6).font('Sans').fontSize(13).fillColor('#d6e4f0').text(this.o.subtitle, { width: PAGE.w - 108 });
    d.fillColor(COLORS.ink).font('Sans-Bold').fontSize(11).text('Synthetic demonstration project', 54, 300);
    d.font('Sans').fontSize(10).fillColor(COLORS.muted).text(
      'Independently designed for engineering-interview preparation. All data is synthetic: no real customer data, credentials, proprietary APIs or confidential processes are used.',
      54, 320, { width: PAGE.w - 108, lineGap: 3 });
    let y = 400;
    for (const line of this.o.coverLines ?? []) {
      d.font('Sans').fontSize(10).fillColor(COLORS.ink).text(line, 54, y, { width: PAGE.w - 108 });
      y = d.y + 6;
    }
    d.font('Sans').fontSize(9).fillColor(COLORS.muted).text(
      'Evidence labels used throughout: Executed = run in this environment with recorded output; Documented / not executed = files or design present but not run here; Conceptual = design discussion only.',
      54, 690, { width: PAGE.w - 108, lineGap: 2 });
  }

  chapterHeading(title) {
    const d = this.doc;
    const dest = `ch-${++this.destSeq}-${slug(title)}`;
    d.addNamedDestination(dest);
    const page = this.currentPageNo();
    this.headings.push({ level: 1, text: title, page, dest });
    this.addOutline(1, title);
    d.font('Sans-Bold').fontSize(22).fillColor(COLORS.navy).text(title, this.left, d.y, { width: this.width });
    d.moveTo(this.left, d.y + 4).lineTo(this.left + this.width, d.y + 4).lineWidth(2).strokeColor(COLORS.accent).stroke();
    d.moveDown(1).fillColor(COLORS.ink).font('Sans').fontSize(BODY);
  }

  addOutline(level, title) {
    const stack = this.outlineStack;
    while (stack.length >= level) stack.pop();
    const parent = stack.length ? stack[stack.length - 1] : this.doc.outline;
    const item = parent.addItem(title);
    stack.push(item);
  }

  heading(level, text, ctx) {
    const d = this.doc;
    const sizes = { 2: 15, 3: 12, 4: 10.5, 5: 10, 6: 10 };
    const size = sizes[level] ?? 10;
    this.ensure(size * 3.2 + 30);
    if (level === 2) d.moveDown(0.6);
    else d.moveDown(0.35);
    if (this.inToc(level, text)) {
      const dest = `h-${++this.destSeq}-${slug(text)}`;
      d.addNamedDestination(dest);
      this.headings.push({ level, text, page: this.currentPageNo(), dest });
      this.addOutline(level, text);
    }
    d.font('Sans-Bold').fontSize(size).fillColor(level <= 2 ? COLORS.navy : COLORS.ink).text(text, ctx.left, d.y, { width: ctx.width });
    if (level === 2) {
      d.moveTo(ctx.left, d.y + 1).lineTo(ctx.left + ctx.width, d.y + 1).lineWidth(0.6).strokeColor(COLORS.rule).stroke();
    }
    d.moveDown(0.3).font('Sans').fontSize(BODY).fillColor(COLORS.ink);
  }

  // ---------- block rendering ----------
  blocks(tokens, ctx) {
    for (let i = 0; i < tokens.length; i++) {
      const t = tokens[i];
      if (t.type === 'heading') {
        // A heading directly followed by a wide (landscape) figure is drawn on the figure's page.
        let j = i + 1;
        while (tokens[j]?.type === 'space') j++;
        const nt = tokens[j];
        const img = nt?.type === 'paragraph' ? nt.tokens?.filter((x) => x.type === 'image') : [];
        if (img?.length === 1 && this.isWide(img[0], ctx)) {
          this.pendingHeading = { t, ctx };
          continue;
        }
      }
      this.block(t, ctx);
    }
  }

  resolveImage(t, ctx) {
    let file = path.resolve(ctx.baseDir, decodeURI(t.href));
    if (/\.svg$/i.test(file)) file = file.replace(/\.svg$/i, '.png');
    return file;
  }

  /** 'landscape' | 'portrait' (dedicated full-page figure) | null (inline figure). */
  figureMode(img, hint) {
    if (hint === 'landscape') return 'landscape';
    if (img.width <= 1300) return null;
    return img.width / img.height > 1.3 ? 'landscape' : 'portrait';
  }

  figureModeOf(t, ctx) {
    const file = this.resolveImage(t, ctx);
    return fs.existsSync(file) ? this.figureMode(this.doc.openImage(file), t.title) : null;
  }

  isWide(t, ctx) {
    const file = this.resolveImage(t, ctx);
    if (!fs.existsSync(file)) return false;
    return this.figureMode(this.doc.openImage(file), t.title) !== null;
  }

  block(t, ctx) {
    const d = this.doc;
    if (this.afterFigure && t.type !== 'space') {
      const isFigure =
        (t.type === 'paragraph' && t.tokens?.length === 1 && t.tokens[0].type === 'image' && this.isWide(t.tokens[0], ctx)) ||
        (t.type === 'code' && t.lang === 'mermaid');
      if (!isFigure) { this.newPage(); this.afterFigure = false; }
      else this.afterFigure = false;
    }
    switch (t.type) {
      case 'space': return;
      case 'heading': {
        const text = this.plain(t.tokens ?? [{ type: 'text', text: t.text }]);
        if (t.depth === 1) return this.heading(2, text, ctx);
        return this.heading(t.depth, text, ctx);
      }
      case 'paragraph': return this.paragraph(t.tokens, ctx);
      case 'text': return this.paragraph(t.tokens ?? [{ type: 'text', text: t.text }], ctx);
      case 'list': return this.list(t, ctx, 0);
      case 'code': return this.code(t, ctx);
      case 'table': return this.table(t, ctx);
      case 'blockquote': return this.blockquote(t, ctx);
      case 'hr': {
        this.ensure(14);
        d.moveDown(0.3);
        d.moveTo(ctx.left, d.y).lineTo(ctx.left + ctx.width, d.y).lineWidth(0.5).strokeColor(COLORS.rule).stroke();
        d.moveDown(0.5);
        return;
      }
      case 'html': return;
      default: return;
    }
  }

  plain(tokens) {
    return decode(
      (tokens ?? []).map((t) => {
        if (t.tokens) return this.plain(t.tokens);
        if (t.type === 'br') return '\n';
        if (t.type === 'image') return t.text ?? '';
        return t.text ?? t.raw ?? '';
      }).join(''));
  }

  runs(tokens, style = { bold: false, italic: false }, out = []) {
    for (const t of tokens ?? []) {
      switch (t.type) {
        case 'text':
        case 'escape':
          if (t.tokens && t.type === 'text') this.runs(t.tokens, style, out);
          else out.push({ text: softBreak(decode(t.text).replace(/\s*\n\s*/g, ' ')), ...style });
          break;
        case 'strong': this.runs(t.tokens, { ...style, bold: true }, out); break;
        case 'em': this.runs(t.tokens, { ...style, italic: true }, out); break;
        case 'del': this.runs(t.tokens, style, out); break;
        case 'codespan': out.push({ text: softBreak(decode(t.text)), code: true, ...style }); break;
        case 'link': {
          const inner = this.runs(t.tokens, style, []);
          const href = t.href;
          const external = /^https?:/i.test(href);
          for (const r of inner) out.push({ ...r, link: external ? href : null, linkStyle: true });
          break;
        }
        case 'br': out.push({ text: '\n', ...style }); break;
        case 'image': out.push({ text: `[${decode(t.text)}]`, ...style, italic: true }); break;
        case 'html': break;
        default: if (t.text) out.push({ text: decode(t.text), ...style });
      }
    }
    // merge trivial empties
    return out.filter((r) => r.text !== '');
  }

  fontFor(r) {
    if (r.code) return r.bold ? 'Mono-Bold' : 'Mono';
    if (r.bold && r.italic) return 'Sans-BoldItalic';
    if (r.bold) return 'Sans-Bold';
    if (r.italic) return 'Sans-Italic';
    return 'Sans';
  }

  writeRuns(runs, x, y, width, { size = BODY, color = COLORS.ink, align = 'left', lineGap = 2.2 } = {}) {
    const d = this.doc;
    if (!runs.length) return;
    runs.forEach((r, i) => {
      d.font(this.fontFor(r)).fontSize(r.code ? size - 0.9 : size);
      d.fillColor(r.code ? '#9a1b4b' : r.linkStyle ? COLORS.link : color);
      const opts = { width, align, lineGap, continued: i < runs.length - 1 };
      if (r.link) { opts.link = r.link; opts.underline = true; }
      if (i === 0) d.text(r.text, x, y, opts);
      else d.text(r.text, opts);
    });
    d.fillColor(COLORS.ink).font('Sans').fontSize(BODY);
  }

  paragraph(tokens, ctx, opts = {}) {
    const d = this.doc;
    // Standalone image paragraph
    const imgs = (tokens ?? []).filter((t) => t.type === 'image');
    if (imgs.length === 1 && (tokens ?? []).every((t) => t.type === 'image' || (t.type === 'text' && !t.text.trim()))) {
      return this.image(imgs[0], ctx);
    }
    const runs = this.runs(tokens);
    if (!runs.length) return;
    const text = runs.map((r) => r.text).join('');
    d.font('Sans').fontSize(BODY);
    this.ensure(Math.min(d.heightOfString(text, { width: ctx.width, lineGap: 2.2 }), 60) + 4);
    this.writeRuns(runs, ctx.left, d.y, ctx.width, opts);
    d.moveDown(opts.tight ? 0.15 : 0.55);
  }

  list(t, ctx, depth) {
    const d = this.doc;
    const indent = 15;
    let n = typeof t.start === 'number' ? t.start : 1;
    for (const item of t.items) {
      const marker = t.ordered ? `${n++}.` : depth % 2 === 0 ? '•' : '–';
      const x0 = ctx.left + depth * 0 ;
      this.ensure(20);
      const y0 = d.y;
      d.font('Sans').fontSize(BODY).fillColor(COLORS.accent).text(marker, x0, y0, { width: indent - 2, lineBreak: false });
      d.fillColor(COLORS.ink);
      const inner = { left: x0 + indent, width: ctx.width - indent, baseDir: ctx.baseDir };
      d.y = y0;
      let first = true;
      for (const sub of item.tokens ?? []) {
        if (sub.type === 'text' || sub.type === 'paragraph') {
          this.paragraph(sub.tokens ?? [{ type: 'text', text: sub.text }], inner, { tight: true });
          first = false;
        } else if (sub.type === 'list') {
          this.list(sub, inner, depth + 1);
        } else if (sub.type === 'space') {
          continue;
        } else {
          this.block(sub, inner);
        }
      }
      if (first) d.moveDown(0.2);
    }
    d.moveDown(0.35);
  }

  code(t, ctx) {
    const d = this.doc;
    this.stats.codeBlocks += 1;
    if (t.lang === 'mermaid') return this.mermaid(t.text, ctx);
    const size = 7.6;
    const pad = 5;
    const text = decode(t.text).replace(/\t/g, '  ');
    const lines = text.split('\n');
    d.font('Mono').fontSize(size);
    let i = 0;
    while (i < lines.length) {
      this.ensure(size * 2.5 + pad * 2);
      let chunk = [];
      let h = pad * 2;
      const avail = this.bottomLimit - d.y - 4;
      while (i < lines.length) {
        const lh = d.heightOfString(lines[i] || ' ', { width: ctx.width - pad * 2, lineGap: 1.2 });
        if (h + lh > avail && chunk.length) break;
        chunk.push(lines[i]); h += lh; i += 1;
      }
      const y0 = d.y;
      d.rect(ctx.left, y0, ctx.width, h).fill(COLORS.codeBg);
      d.rect(ctx.left, y0, 2.5, h).fill(COLORS.accent);
      d.fillColor(COLORS.codeInk).font('Mono').fontSize(size).text(chunk.join('\n'), ctx.left + pad + 2, y0 + pad, { width: ctx.width - pad * 2 - 2, lineGap: 1.2 });
      d.y = y0 + h + 4;
    }
    d.moveDown(0.4).font('Sans').fontSize(BODY).fillColor(COLORS.ink);
  }

  mermaid(code, ctx) {
    this.stats.mermaid += 1;
    const png = this.o.renderMermaid ? this.o.renderMermaid(code) : null;
    if (!png) {
      this.stats.mermaidFailed += 1;
      return this.code({ type: 'code', lang: 'text', text: code }, ctx);
    }
    const title = /title:\s*(.+)/.exec(code)?.[1]?.trim() ?? 'Diagram';
    this.placeImage(png, title, ctx);
  }

  image(t, ctx) {
    const href = decodeURI(t.href);
    let file = path.resolve(ctx.baseDir, href);
    if (/\.svg$/i.test(file)) file = file.replace(/\.svg$/i, '.png');
    if (!fs.existsSync(file)) {
      this.stats.missingImages.push(file);
      return this.paragraph([{ type: 'text', text: `[missing image: ${href}]` }], ctx);
    }
    this.placeImage(file, decode(t.text || t.title || path.basename(file)), ctx, t.title);
  }

  placeImage(file, caption, ctx, hint) {
    const d = this.doc;
    this.stats.images += 1;
    const img = d.openImage(file);
    const aspect = img.width / img.height;
    let mode = this.figureMode(img, hint);
    this.figureNo += 1;
    const cap = `Figure ${this.figureNo}. ${caption}`;
    const reuse = this.reuseFigurePage;
    this.reuseFigurePage = false;
    // A portrait figure right after a short chapter intro simply continues on the same page.
    if (mode === 'portrait' && !reuse && !this.pendingHeading && this.doc.y < this.doc.page.margins.top + 160) mode = null;
    if (mode) {
      // Dedicated page (landscape for wide diagrams, full-width portrait otherwise) so labels stay legible.
      if (reuse) { /* chapter page already created in the right layout */ }
      else if (mode === 'landscape') this.newPage({ layout: 'landscape', margins: { left: 36, right: 36, top: 40, bottom: 70 } });
      else this.newPage({ margins: { left: 30, right: 30, top: 40, bottom: 70 } });
      if (this.pendingHeading) {
        const { t, ctx: hctx } = this.pendingHeading;
        this.pendingHeading = null;
        const text = this.plain(t.tokens ?? [{ type: 'text', text: t.text }]);
        this.heading(t.depth === 1 ? 2 : t.depth, text, { ...hctx, left: this.left, width: this.width });
      }
      const w = this.width;
      const maxH = this.bottomLimit - d.y - 30;
      let drawW = w;
      let drawH = w / aspect;
      if (drawH > maxH) { drawH = maxH; drawW = maxH * aspect; }
      const x = this.left + (w - drawW) / 2;
      const y = d.y;
      d.image(file, x, y, { width: drawW, height: drawH });
      d.y = y + drawH + 6;
      d.font('Sans-Italic').fontSize(8.5).fillColor(COLORS.muted).text(cap, this.left, d.y, { width: w, align: 'center' });
      this.afterFigure = true;
      return;
    }
    const w = ctx.width;
    let drawW = w;
    let drawH = w / aspect;
    const maxH = this.doc.page.height - M.top - M.bottom - 40;
    if (drawH > maxH) { drawH = maxH; drawW = maxH * aspect; }
    this.ensure(drawH + 30);
    const x = ctx.left + (w - drawW) / 2;
    const y = d.y + 2;
    d.image(file, x, y, { width: drawW, height: drawH });
    d.y = y + drawH + 5;
    d.font('Sans-Italic').fontSize(8.5).fillColor(COLORS.muted).text(cap, ctx.left, d.y, { width: w, align: 'center' });
    d.moveDown(0.7).font('Sans').fontSize(BODY).fillColor(COLORS.ink);
  }

  blockquote(t, ctx) {
    const d = this.doc;
    const start = d.y;
    const inner = { left: ctx.left + 12, width: ctx.width - 16, baseDir: ctx.baseDir };
    // Measure by rendering into page then drawing the bar behind is not possible; draw bar after.
    const startPage = this.currentPageNo();
    this.blocks(t.tokens ?? [], inner);
    if (this.currentPageNo() === startPage) {
      d.rect(ctx.left, start, 3, Math.max(d.y - start - 4, 8)).fill(COLORS.accent);
    }
    d.fillColor(COLORS.ink);
    d.moveDown(0.2);
  }

  table(t, ctx) {
    const d = this.doc;
    this.stats.tables += 1;
    const cols = t.header.length;
    const cellText = (c) => this.runs(c.tokens);
    const plain = (c) => softBreak(decode(this.plain(c.tokens)));
    const rows = t.rows;
    // column weights
    const weights = new Array(cols).fill(0);
    const longest = new Array(cols).fill(0);
    const fs8 = 8.1;
    const pad = 4;
    const sample = [t.header, ...rows.slice(0, 80)];
    sample.forEach((row, ri) => {
      d.font(ri === 0 ? 'Sans-Bold' : 'Sans').fontSize(fs8);
      row.forEach((c, i) => {
        const p = plain(c);
        weights[i] = Math.max(weights[i], Math.min(p.length, 70));
        d.font('Sans-Bold').fontSize(fs8);
        for (const w of p.split(/\s+/)) longest[i] = Math.max(longest[i], d.widthOfString(w));
      });
    });
    d.font('Sans').fontSize(fs8);
    const minW = longest.map((l) => Math.min(l + pad * 2 + 3, ctx.width * 0.45));
    const sumW = weights.reduce((a, b) => a + Math.max(b, 6), 0);
    let widths = weights.map((w, i) => Math.max((Math.max(w, 6) / sumW) * ctx.width, minW[i]));
    const total = widths.reduce((a, b) => a + b, 0);
    widths = widths.map((w) => (w / total) * ctx.width);

    const rowHeight = (cells, bold) => {
      let h = 0;
      d.font(bold ? 'Sans-Bold' : 'Sans').fontSize(fs8);
      cells.forEach((c, i) => {
        const text = plain(c);
        h = Math.max(h, d.heightOfString(text || ' ', { width: widths[i] - pad * 2, lineGap: 1.5 }));
      });
      return h + pad * 2;
    };
    const drawRow = (cells, y, h, kind, idx) => {
      let x = ctx.left;
      cells.forEach((c, i) => {
        if (kind === 'head') d.rect(x, y, widths[i], h).fill(COLORS.tableHead);
        else if (idx % 2 === 1) d.rect(x, y, widths[i], h).fill(COLORS.tableAlt);
        d.rect(x, y, widths[i], h).lineWidth(0.4).strokeColor(COLORS.rule).stroke();
        if (kind === 'head') {
          d.fillColor('#ffffff').font('Sans-Bold').fontSize(fs8).text(plain(c), x + pad, y + pad, { width: widths[i] - pad * 2, lineGap: 1.5 });
        } else {
          this.writeRuns(cellText(c), x + pad, y + pad, widths[i] - pad * 2, { size: fs8, lineGap: 1.5 });
        }
        x += widths[i];
      });
      d.fillColor(COLORS.ink).font('Sans').fontSize(BODY);
    };

    d.moveDown(0.2);
    const headH = rowHeight(t.header, true);
    this.ensure(headH + 30);
    let y = d.y;
    drawRow(t.header, y, headH, 'head', 0);
    y += headH;
    rows.forEach((row, idx) => {
      const h = rowHeight(row, false);
      if (y + h > this.bottomLimit) {
        this.newPage();
        y = d.y;
        drawRow(t.header, y, headH, 'head', 0);
        y += headH;
      }
      drawRow(row, y, h, 'body', idx);
      y += h;
    });
    d.y = y + 8;
    d.x = ctx.left;
    d.font('Sans').fontSize(BODY).fillColor(COLORS.ink);
  }

  // ---------- TOC and footers ----------
  writeToc(perPage) {
    const d = this.doc;
    const entries = this.headings;
    let idx = 0;
    for (let p = 0; p < this.tocPages; p++) {
      d.switchToPage(this.tocStartIndex + p);
      let y = M.top;
      if (p === 0) {
        d.font('Sans-Bold').fontSize(20).fillColor(COLORS.navy).text('Table of contents', M.left, y, { width: this.width });
        y = d.y + 14;
      }
      for (let k = 0; k < perPage && idx < entries.length; k++, idx++) {
        const e = entries[idx];
        const indent = e.level === 1 ? 0 : 14;
        const size = e.level === 1 ? 9.6 : 8.6;
        const font = e.level === 1 ? 'Sans-Bold' : 'Sans';
        d.font(font).fontSize(size).fillColor(e.level === 1 ? COLORS.navy : COLORS.ink);
        const pageStr = String(e.page);
        const pageW = d.widthOfString(pageStr);
        const maxTitleW = this.width - indent - pageW - 18;
        let title = e.text;
        while (d.widthOfString(title) > maxTitleW && title.length > 4) title = title.slice(0, -2);
        if (title !== e.text) title = title.trimEnd() + '…';
        const x = M.left + indent;
        d.text(title, x, y, { lineBreak: false });
        const titleW = d.widthOfString(title);
        d.font('Sans').fontSize(size).fillColor(COLORS.muted);
        const dotW = d.widthOfString(' .');
        const dots = Math.max(Math.floor((this.width - indent - titleW - pageW - 10) / dotW), 0);
        d.text(' .'.repeat(dots), x + titleW + 2, y, { lineBreak: false });
        d.fillColor(COLORS.ink).font(font).fontSize(size).text(pageStr, M.left + this.width - pageW, y, { lineBreak: false });
        d.goTo(M.left, y - 1, this.width, size + 4, e.dest);
        y += e.level === 1 ? 17 : 14.2;
      }
    }
  }

  stampFooters() {
    const d = this.doc;
    const range = d.bufferedPageRange();
    const total = range.count;
    for (let i = 0; i < total; i++) {
      d.switchToPage(i);
      const pg = d.page;
      const saved = pg.margins.bottom;
      pg.margins.bottom = 0;
      const w = pg.width - M.left - M.right;
      const yBase = pg.height - 62;
      d.moveTo(M.left, yBase - 4).lineTo(M.left + w, yBase - 4).lineWidth(0.4).strokeColor(COLORS.rule).stroke();
      if (i > 0) {
        d.font('Sans').fontSize(7).fillColor(COLORS.muted).text(this.o.title, M.left, yBase, { width: w * 0.75, lineBreak: false });
        d.text(`Page ${i + 1} of ${total}`, M.left, yBase, { width: w, align: 'right', lineBreak: false });
      }
      d.font('Sans-Italic').fontSize(5.9).fillColor('#777f8b');
      (this.o.footerLines ?? []).forEach((line, k) => {
        d.text(line, M.left, yBase + 13 + k * 9, { width: w, align: 'center', lineBreak: false });
      });
      pg.margins.bottom = saved;
    }
  }
}

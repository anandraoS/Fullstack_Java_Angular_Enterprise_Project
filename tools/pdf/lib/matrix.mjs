// Converts the 6-column traceability table into one readable card per requirement (a 6-column table is
// unreadable on a portrait A4 page). The Markdown file itself keeps the table form.
import { read, stripLocalLinks } from './common.mjs';

export function matrixCards() {
  const md = stripLocalLinks(read('docs/traceability/job-description-traceability-matrix.md'));
  const lines = md.split('\n');
  const intro = [];
  const rows = [];
  let header = null;
  for (const ln of lines) {
    if (/^#\s/.test(ln)) continue;
    if (ln.startsWith('|')) {
      const cells = ln.slice(1, ln.lastIndexOf('|')).split(/(?<!\\)\|/).map((c) => c.trim());
      if (!header) header = cells;
      else if (!/^-+$/.test(cells[0].replace(/\s|:/g, ''))) rows.push(cells);
    } else if (!header) intro.push(ln);
  }
  const out = [intro.join('\n').trim(), '', `${rows.length} requirements are mapped. Each card lists: ${header.slice(1).join('; ').toLowerCase()}.`, ''];
  for (const r of rows) {
    out.push(`### ${r[0]}`, '', '| Field | Detail |', '|---|---|');
    for (let i = 1; i < header.length; i++) out.push(`| **${header[i]}** | ${r[i] ?? ''} |`);
    out.push('');
  }
  return out.join('\n');
}

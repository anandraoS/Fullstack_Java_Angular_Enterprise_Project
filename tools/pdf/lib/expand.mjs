// Markdown include expansion shared by the build scripts.
//   {{code:<repo path>|<start regex>|<line count>|<lang>}}   copies real lines from a file (build fails if not found)
//   {{diagram:<stem>|<caption>}}                              embeds docs/diagrams/rendered/<stem>.png
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, read, diagram } from './common.mjs';

export const usedFiles = new Set();

export function expandIncludes(md, where = '') {
  md = md.replace(/\{\{code:([^|}]+)\|([^|}]*)\|(\d+)\|(\w+)\}\}/g, (_m, rel, pattern, n, lang) => {
    const file = path.join(ROOT, rel);
    if (!fs.existsSync(file)) throw new Error(`code include: missing file ${rel} (in ${where})`);
    usedFiles.add(rel);
    const lines = read(rel).split('\n');
    const re = new RegExp(pattern);
    const start = lines.findIndex((l) => re.test(l));
    if (start < 0) throw new Error(`code include: pattern /${pattern}/ not found in ${rel} (in ${where})`);
    const chunk = lines.slice(start, start + Number(n));
    while (chunk.length && chunk[chunk.length - 1].trim() === '') chunk.pop();
    const cap = `*Source: \`${rel}\` (lines ${start + 1} to ${start + chunk.length})*`;
    return `${cap}\n\n\`\`\`${lang}\n${chunk.join('\n')}\n\`\`\``;
  });
  md = md.replace(/\{\{diagram:([^|}]+)\|([^}]+)\}\}/g, (_m, stem, caption) => diagram(stem, caption));
  return md;
}

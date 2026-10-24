const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const p2 = (n: number) => String(n).padStart(2, '0');

/** Money: two decimals for numbers; `fallback` for null (prices of REJECTED orders are null, defect D-03). */
export function money(v: unknown, fallback = ''): string {
  return typeof v === 'number' ? v.toFixed(2) : v == null ? fallback : String(v);
}

export type DateStyle = 'table' | 'short' | 'time' | 'medium';

/** Local-time date formatting without locale surprises (e.g. "Oct 19, 2026, 14:03:07"). Invalid/empty input gives ''. */
export function fmtDate(iso: string | null | undefined, style: DateStyle = 'table'): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const day = `${MONTHS[d.getMonth()]} ${d.getDate()}`;
  const hms = `${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`;
  switch (style) {
    case 'time':
      return hms;
    case 'short':
      return `${day}, ${p2(d.getHours())}:${p2(d.getMinutes())}`;
    default:
      return `${day}, ${d.getFullYear()}, ${hms}`;
  }
}

export const shortId = (id: string | null | undefined): string => (id ?? '').slice(0, 8);

export const humanize = (s: string): string => s.replace(/_/g, ' ');

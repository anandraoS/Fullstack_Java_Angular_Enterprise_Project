// Minimal JSON Schema validator (subset used by specs/events/*.schema.json): type (string|array), const, enum, required,
// properties, items, minItems, minimum, minLength, pattern, format (uuid|date-time). additionalProperties is NOT restricted
// (tolerant reader). Not a general validator; no $ref. Returns an array of error strings (empty = valid).
const typeOk = (t, v) => {
  switch (t) {
    case 'string': return typeof v === 'string';
    case 'number': return typeof v === 'number' && Number.isFinite(v);
    case 'integer': return Number.isInteger(v);
    case 'boolean': return typeof v === 'boolean';
    case 'null': return v === null;
    case 'array': return Array.isArray(v);
    case 'object': return v !== null && typeof v === 'object' && !Array.isArray(v);
    default: return false;
  }
};
export function validate(schema, value, at = '$') {
  const errs = [];
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((t) => typeOk(t, value))) return [`${at}: expected ${types.join('|')} but got ${value === null ? 'null' : typeof value}`];
  }
  if ('const' in schema && JSON.stringify(schema.const) !== JSON.stringify(value)) errs.push(`${at}: expected const ${JSON.stringify(schema.const)} but got ${JSON.stringify(value)}`);
  if (schema.enum && !schema.enum.includes(value)) errs.push(`${at}: ${JSON.stringify(value)} not in enum [${schema.enum.join(', ')}]`);
  if (typeof value === 'string') {
    if (schema.minLength != null && value.length < schema.minLength) errs.push(`${at}: shorter than ${schema.minLength}`);
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) errs.push(`${at}: does not match ${schema.pattern}`);
    if (schema.format === 'date-time' && Number.isNaN(Date.parse(value))) errs.push(`${at}: not an ISO date-time`);
  }
  if (typeof value === 'number') {
    if (schema.minimum != null && value < schema.minimum) errs.push(`${at}: ${value} < minimum ${schema.minimum}`);
  }
  if (Array.isArray(value)) {
    if (schema.minItems != null && value.length < schema.minItems) errs.push(`${at}: fewer than ${schema.minItems} items`);
    if (schema.items) value.forEach((v, i) => errs.push(...validate(schema.items, v, `${at}[${i}]`)));
  }
  if (typeOk('object', value)) {
    for (const r of schema.required ?? []) if (!(r in value) || value[r] === undefined) errs.push(`${at}: missing required property "${r}"`);
    for (const [k, s] of Object.entries(schema.properties ?? {})) if (k in value) errs.push(...validate(s, value[k], `${at}.${k}`));
  }
  return errs;
}

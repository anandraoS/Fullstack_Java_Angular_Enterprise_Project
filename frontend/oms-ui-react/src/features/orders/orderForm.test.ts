import {
  IdempotencyKeyHolder,
  MAX_ITEMS,
  buildRequest,
  duplicateProductIds,
  fingerprint,
  generateIdempotencyKey,
  isValidIdempotencyKey,
  isValidQuantity,
  orderFormSchema,
  splitServerErrors,
  toFormPath,
  type OrderFormValues,
} from './orderForm';
import { stockAdjustSchema } from '../inventory/stockAdjust';
import { dateBound, dateRangeInvalid } from './OrderList';

const valid = (over: Partial<OrderFormValues> = {}): OrderFormValues => ({
  customerId: 'c1',
  items: [{ productId: 'p1', quantity: '2' }],
  shippingAddress: { line1: ' 1 Test St ', city: 'Springfield', postalCode: '12345', country: 'us' },
  ...over,
});
const firstMessage = (v: OrderFormValues): string | undefined => {
  const r = orderFormSchema.safeParse(v);
  return r.success ? undefined : r.error.issues[0].message;
};

describe('orderFormSchema (zod)', () => {
  it('accepts a valid order', () => {
    expect(orderFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('requires customer, product and address fields with friendly messages', () => {
    expect(firstMessage(valid({ customerId: '' }))).toBe('Select a customer.');
    expect(firstMessage(valid({ items: [{ productId: '', quantity: '1' }] }))).toBe('Select a product.');
    expect(firstMessage(valid({ shippingAddress: { ...valid().shippingAddress, line1: '  ' } }))).toBe('Address line is required.');
    expect(firstMessage(valid({ shippingAddress: { ...valid().shippingAddress, city: '' } }))).toBe('City is required.');
  });

  it.each(['', ' ', '0', '-1', '1.5', 'abc', '1001'])('rejects quantity %j', (q) => {
    expect(firstMessage(valid({ items: [{ productId: 'p1', quantity: q }] }))).toBe('Enter a whole number from 1 to 1000.');
    expect(isValidQuantity(q)).toBe(false);
  });

  it.each(['1', '2', '1000'])('accepts quantity %s', (q) => expect(isValidQuantity(q)).toBe(true));

  it.each(['12', '12!45', ' ', 'a'.repeat(11)])('rejects postal code %j', (p) => {
    expect(firstMessage(valid({ shippingAddress: { ...valid().shippingAddress, postalCode: p } }))).toMatch(/postal code|required|Enter a valid/i);
  });

  it.each(['U', 'USA', '12', ''])('rejects country %j', (c) => {
    expect(firstMessage(valid({ shippingAddress: { ...valid().shippingAddress, country: c } }))).toBe('Use a 2-letter country code, for example US.');
  });

  it('rejects duplicate products and more than 50 lines, and requires at least one line', () => {
    expect(firstMessage(valid({ items: [{ productId: 'p1', quantity: '1' }, { productId: 'p1', quantity: '2' }] }))).toMatch(/same product/);
    expect(duplicateProductIds([{ productId: 'a' }, { productId: 'b' }, { productId: 'a' }, { productId: '' }, { productId: '' }])).toEqual(['a']);
    const many = Array.from({ length: MAX_ITEMS + 1 }, (_, i) => ({ productId: `p${i}`, quantity: '1' }));
    expect(firstMessage(valid({ items: many }))).toMatch(/at most 50 lines/);
    expect(firstMessage(valid({ items: [] }))).toBe('Add at least one item.');
  });

  it('buildRequest trims, upper-cases the country and converts quantities to numbers', () => {
    expect(buildRequest(valid())).toEqual({
      customerId: 'c1',
      items: [{ productId: 'p1', quantity: 2 }],
      shippingAddress: { line1: '1 Test St', city: 'Springfield', postalCode: '12345', country: 'US' },
    });
  });
});

describe('IdempotencyKeyHolder', () => {
  const req = buildRequest(valid());
  let n = 0;
  const gen = () => `key-${String(++n).padStart(8, '0')}`;

  beforeEach(() => {
    n = 0;
  });

  it('generated keys satisfy the contract pattern (8-64 chars of [A-Za-z0-9_-])', () => {
    const k = generateIdempotencyKey();
    expect(isValidIdempotencyKey(k)).toBe(true);
    expect(k).not.toBe(generateIdempotencyKey());
    expect(isValidIdempotencyKey('short')).toBe(false);
    expect(isValidIdempotencyKey('has space in it')).toBe(false);
    expect(isValidIdempotencyKey('a'.repeat(65))).toBe(false);
  });

  it('reuses the key for an unchanged body until the attempt is settled (unknown outcome -> retry is idempotent)', () => {
    const h = new IdempotencyKeyHolder(gen);
    const k1 = h.keyFor(req);
    expect(h.keyFor(req)).toBe(k1); // retry after network error: SAME key
    expect(h.keyFor({ ...req })).toBe(k1);
    expect(h.current).toBe(k1);
  });

  it('uses a NEW key for a new logical submission (after a definite answer)', () => {
    const h = new IdempotencyKeyHolder(gen);
    const k1 = h.keyFor(req);
    h.settle();
    expect(h.current).toBeNull();
    expect(h.keyFor(req)).not.toBe(k1);
  });

  it('uses a NEW key when the body changed (same key + other body would be 422)', () => {
    const h = new IdempotencyKeyHolder(gen);
    const k1 = h.keyFor(req);
    const changed = { ...req, items: [{ productId: 'p1', quantity: 3 }] };
    expect(h.keyFor(changed)).not.toBe(k1);
  });

  it('the fingerprint ignores line order but not content', () => {
    const a = { ...req, items: [{ productId: 'a', quantity: 1 }, { productId: 'b', quantity: 2 }] };
    const b = { ...req, items: [{ productId: 'b', quantity: 2 }, { productId: 'a', quantity: 1 }] };
    expect(fingerprint(a)).toBe(fingerprint(b));
    expect(fingerprint(a)).not.toBe(fingerprint({ ...a, shippingAddress: { ...a.shippingAddress, city: 'Other' } }));
  });
});

describe('server field errors', () => {
  it('maps api paths to form paths and separates unmatched messages', () => {
    expect(toFormPath('items[0].quantity')).toBe('items.0.quantity');
    const { mapped, unmatched } = splitServerErrors([
      { field: 'items[2].quantity', message: 'must be <= 1000' },
      { field: 'shippingAddress.city', message: 'required' },
      { field: 'customerId', message: 'unknown' },
      { field: 'something.else', message: 'odd' },
      { field: 'items[0].sku', message: 'not a form field' },
    ]);
    expect(mapped).toEqual([
      { path: 'items.2.quantity', message: 'must be <= 1000' },
      { path: 'shippingAddress.city', message: 'required' },
      { path: 'customerId', message: 'unknown' },
    ]);
    expect(unmatched).toEqual(['something.else: odd', 'items[0].sku: not a form field']);
  });
});

describe('stockAdjustSchema (zod)', () => {
  const schema = stockAdjustSchema(100, 40);
  const msg = (delta: string, reason = 'cycle count') => {
    const r = schema.safeParse({ delta, reason });
    return r.success ? undefined : r.error.issues[0].message;
  };

  it('requires a whole, non-zero change that keeps on-hand at or above reserved', () => {
    expect(msg('')).toBe('Enter a number.');
    expect(msg('abc')).toBe('Enter a number.');
    expect(msg('1.5')).toBe('Whole numbers only.');
    expect(msg('0')).toBe('The change must not be zero.');
    expect(msg('-61')).toBe('Stock cannot fall below the reserved quantity.');
    expect(msg('-60')).toBeUndefined(); // exactly reserved is fine
    expect(msg('5')).toBeUndefined();
  });

  it('requires a reason of at least 5 characters for the audit trail', () => {
    expect(msg('5', 'abc')).toMatch(/at least 5 characters/);
    expect(msg('5', '    ab')).toMatch(/at least 5 characters/);
    expect(msg('5', 'x'.repeat(201))).toMatch(/At most 200/);
  });
});

describe('order list filter helpers', () => {
  it('dateBound builds UTC day bounds and ignores empty input', () => {
    expect(dateBound('2026-10-19', false)).toBe('2026-10-19T00:00:00Z');
    expect(dateBound('2026-10-19', true)).toBe('2026-10-19T23:59:59Z');
    expect(dateBound('', true)).toBe('');
  });
  it('dateRangeInvalid flags an end before the start only', () => {
    expect(dateRangeInvalid('2026-10-20', '2026-10-19')).toBe(true);
    expect(dateRangeInvalid('2026-10-19', '2026-10-19')).toBe(false);
    expect(dateRangeInvalid('', '2026-10-19')).toBe(false);
  });
});

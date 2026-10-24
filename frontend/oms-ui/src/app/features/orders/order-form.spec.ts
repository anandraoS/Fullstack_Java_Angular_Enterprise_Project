import { FormArray, FormControl, FormGroup } from '@angular/forms';
import { ADDRESS } from '../../testing/test-utils';
import {
  IdempotencyKeyHolder,
  applyServerErrors,
  countryCode,
  fingerprint,
  generateIdempotencyKey,
  isValidIdempotencyKey,
  itemCount,
  postalCode,
  quantityRange,
  uniqueProducts,
} from './order-form';

const row = (productId: string, quantity = 1) => new FormGroup({ productId: new FormControl(productId), quantity: new FormControl(quantity) });

describe('order form validators', () => {
  it('quantityRange enforces whole numbers within 1..1000', () => {
    const v = quantityRange();
    expect(v(new FormControl(1))).toBeNull();
    expect(v(new FormControl(1000))).toBeNull();
    expect(v(new FormControl(0))).toEqual({ min: { min: 1, actual: 0 } });
    expect(v(new FormControl(1001))).toEqual({ max: { max: 1000, actual: 1001 } });
    expect(v(new FormControl(2.5))).toEqual({ integer: true });
    expect(v(new FormControl(-3))).toEqual({ min: { min: 1, actual: -3 } });
    expect(v(new FormControl(null))).toBeNull(); // required handles empties
  });

  it('postalCode and countryCode', () => {
    expect(postalCode(new FormControl('12345'))).toBeNull();
    expect(postalCode(new FormControl('SW1A 1AA'))).toBeNull();
    expect(postalCode(new FormControl('1'))).toEqual({ postalCode: true });
    expect(postalCode(new FormControl('<script>'))).toEqual({ postalCode: true });
    expect(countryCode(new FormControl('US'))).toBeNull();
    expect(countryCode(new FormControl('usa'))).toEqual({ countryCode: true });
  });

  it('itemCount enforces 1..50 lines', () => {
    const v = itemCount();
    expect(v(new FormArray([]))).toEqual({ minItems: { min: 1 } });
    expect(v(new FormArray([row('a')]))).toBeNull();
    expect(v(new FormArray(Array.from({ length: 51 }, (_, i) => row('p' + i))))).toEqual({ maxItems: { max: 50 } });
  });

  it('uniqueProducts flags duplicate product ids but ignores empty selections', () => {
    expect(uniqueProducts(new FormArray([row('a'), row('b')]))).toBeNull();
    expect(uniqueProducts(new FormArray([row('a'), row('a')]))).toEqual({ duplicateProduct: ['a'] });
    expect(uniqueProducts(new FormArray([row(''), row('')]))).toBeNull();
  });
});

describe('idempotency keys', () => {
  const req = (qty: number) => ({ customerId: 'c', items: [{ productId: 'p', quantity: qty }], shippingAddress: ADDRESS });

  it('generated keys satisfy the contract (8-64 chars of [A-Za-z0-9_-])', () => {
    const k = generateIdempotencyKey();
    expect(isValidIdempotencyKey(k)).toBeTrue();
    expect(generateIdempotencyKey()).not.toBe(k);
    expect(isValidIdempotencyKey('short')).toBeFalse();
    expect(isValidIdempotencyKey('has space in it!')).toBeFalse();
  });

  it('fingerprint ignores item order', () => {
    const a = { ...req(1), items: [{ productId: 'a', quantity: 1 }, { productId: 'b', quantity: 2 }] };
    const b = { ...req(1), items: [{ productId: 'b', quantity: 2 }, { productId: 'a', quantity: 1 }] };
    expect(fingerprint(a)).toBe(fingerprint(b));
  });

  it('reuses the key for an unchanged body until settled, and renews it after a change or settle', () => {
    let n = 0;
    const holder = new IdempotencyKeyHolder(() => `key-${++n}-padding`);
    const k1 = holder.keyFor(req(1));
    expect(holder.keyFor(req(1))).toBe(k1); // retry after an unknown outcome
    expect(holder.keyFor(req(2))).not.toBe(k1); // body changed: new key (else 422 IDEMPOTENCY_KEY_REUSED)
    const k3 = holder.keyFor(req(2));
    holder.settle();
    expect(holder.current).toBeNull();
    expect(holder.keyFor(req(2))).not.toBe(k3);
  });
});

describe('applyServerErrors', () => {
  it('maps server field paths to controls and returns the rest', () => {
    const form = new FormGroup({
      items: new FormArray([new FormGroup({ quantity: new FormControl(1) })]),
      shippingAddress: new FormGroup({ city: new FormControl('') }),
    });
    const unmatched = applyServerErrors(form, [
      { field: 'items[0].quantity', message: 'too big' },
      { field: 'shippingAddress.city', message: 'unsupported' },
      { field: 'nothing', message: 'x' },
    ]);
    expect(form.get('items.0.quantity')?.errors).toEqual({ server: 'too big' });
    expect(form.get('shippingAddress.city')?.hasError('server')).toBeTrue();
    expect(unmatched).toEqual(['nothing: x']);
  });
});

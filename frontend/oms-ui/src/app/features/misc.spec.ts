import { NAV_ITEMS, visibleNav } from '../app';
import { makeOrder } from '../testing/test-utils';
import { countByStatus } from './dashboard/dashboard';
import { safeReturnUrl } from './login/login';
import { dateBound } from './orders/order-list';
import { totalLag } from './ops/ops-dashboard';
import { nonZeroInteger, notBelowReserved } from './inventory/stock-adjust-dialog';
import { FormControl } from '@angular/forms';

describe('role-aware navigation', () => {
  const labels = (roles: Parameters<typeof visibleNav>[1]) => visibleNav(NAV_ITEMS, roles).map((i) => i.label);

  it('customers see no staff screens', () => {
    const l = labels(['CUSTOMER']);
    expect(l).toEqual(['Dashboard', 'Catalog', 'Orders', 'New order', 'Notifications']);
  });

  it('operators and admins see inventory, ops, failed orders, audit and fulfillment', () => {
    for (const r of ['OPERATOR', 'ADMIN'] as const) {
      const l = labels([r]);
      expect(l).toEqual(jasmine.arrayContaining(['Inventory', 'Operations', 'Failed orders', 'Audit history', 'Fulfillment']));
    }
  });

  it('no roles means only public items', () => {
    expect(labels([]).length).toBe(5);
  });
});

describe('small pure helpers', () => {
  it('countByStatus', () => {
    const c = countByStatus([makeOrder({ status: 'FULFILLED' }), makeOrder({ status: 'FULFILLED' }), makeOrder({ status: 'FAILED' })]);
    expect(c).toEqual({ FULFILLED: 2, FAILED: 1 });
  });

  it('safeReturnUrl blocks open redirects', () => {
    expect(safeReturnUrl('/orders/1')).toBe('/orders/1');
    expect(safeReturnUrl('https://evil.test')).toBe('/dashboard');
    expect(safeReturnUrl('//evil.test')).toBe('/dashboard');
    expect(safeReturnUrl(undefined)).toBe('/dashboard');
    expect(safeReturnUrl('/x?next=http://evil.test')).toBe('/dashboard');
  });

  it('dateBound builds inclusive UTC bounds', () => {
    expect(dateBound('2026-03-01', false)).toBe('2026-03-01T00:00:00Z');
    expect(dateBound('2026-03-01', true)).toBe('2026-03-01T23:59:59Z');
    expect(dateBound('', true)).toBe('');
  });

  it('totalLag sums known lags and reports unavailable', () => {
    const t = (consumerLag: number | null) => ({ topic: 't', partitions: 3, consumerLag, committedOffsets: {} });
    expect(totalLag({ topics: [t(2), t(5)] })).toBe(7);
    expect(totalLag({ topics: [t(null)] })).toBeNull();
    expect(totalLag({ topics: [t(3)], lagAvailable: false })).toBeNull();
    expect(totalLag({ topics: [] })).toBeNull();
  });

  it('stock adjustment validators', () => {
    expect(nonZeroInteger(new FormControl(5))).toBeNull();
    expect(nonZeroInteger(new FormControl(-5))).toBeNull();
    expect(nonZeroInteger(new FormControl(0))).toEqual({ nonZero: true });
    expect(nonZeroInteger(new FormControl(1.5))).toEqual({ integer: true });
    expect(nonZeroInteger(new FormControl(null))).toBeNull();
    const v = notBelowReserved(100, 30);
    expect(v(new FormControl(-70))).toBeNull();
    expect(v(new FormControl(-71))).toEqual({ belowReserved: { min: -70 } }); // min = smallest allowed change
  });
});

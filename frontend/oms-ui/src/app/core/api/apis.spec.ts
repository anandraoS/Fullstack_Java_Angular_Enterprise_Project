import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API, httpTestProviders, makeOrder, makeProduct, page } from '../../testing/test-utils';
import { FulfillmentApi, InventoryApi, NotificationApi, OpsApi, OrderApi, toParams } from './apis';

describe('API services', () => {
  let mock: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: httpTestProviders() });
    mock = TestBed.inject(HttpTestingController);
  });
  afterEach(() => mock.verify());

  it('toParams drops empty values', () => {
    const p = toParams({ a: 'x', b: '', c: undefined, d: null, e: 0, f: false });
    expect(p.keys().sort()).toEqual(['a', 'e', 'f']);
  });

  describe('OrderApi', () => {
    it('list sends only non-empty filters plus paging and sort', () => {
      TestBed.inject(OrderApi).list({ status: 'FULFILLED', q: '', page: 1, size: 20, sort: 'createdAt,desc' }).subscribe();
      const req = mock.expectOne((r) => r.url === `${API}/orders`);
      expect(req.request.params.get('status')).toBe('FULFILLED');
      expect(req.request.params.has('q')).toBeFalse();
      expect(req.request.params.get('sort')).toBe('createdAt,desc');
      req.flush(page([makeOrder()]));
    });

    it('create posts the body with the Idempotency-Key and surfaces replay + location', () => {
      let out: { replay: boolean; location: string | null; id: string } | undefined;
      TestBed.inject(OrderApi)
        .create({ customerId: 'c', items: [{ productId: 'p', quantity: 1 }], shippingAddress: makeOrder().shippingAddress }, 'key-123456789')
        .subscribe((r) => (out = { replay: r.replay, location: r.location, id: r.order.id }));
      const req = mock.expectOne(`${API}/orders`);
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Idempotency-Key')).toBe('key-123456789');
      req.flush(makeOrder({ id: 'o-1' }), { status: 202, statusText: 'Accepted', headers: { 'Idempotent-Replay': 'true', Location: '/api/v1/orders/o-1' } });
      expect(out).toEqual({ replay: true, location: '/api/v1/orders/o-1', id: 'o-1' });
    });

    it('cancel, retry, saga, reconcile and customers hit the contract paths', () => {
      const api = TestBed.inject(OrderApi);
      api.cancel('o 1', 'changed mind').subscribe();
      const c = mock.expectOne(`${API}/orders/o%201/cancel`);
      expect(c.request.body).toEqual({ reason: 'changed mind' });
      c.flush(makeOrder());
      api.retry('o1').subscribe();
      mock.expectOne(`${API}/admin/orders/o1/retry`).flush({});
      api.saga('o1').subscribe();
      mock.expectOne(`${API}/admin/saga/o1`).flush({});
      api.reconcile().subscribe();
      mock.expectOne(`${API}/admin/reconciliation/run`).flush({ examined: 1, repaired: 0, details: [] });
      api.customers({ q: 'acme', page: 0, size: 100 }).subscribe();
      mock.expectOne((r) => r.url === `${API}/customers` && r.params.get('q') === 'acme').flush(page([]));
      api.failed({ page: 0, size: 20 }).subscribe();
      mock.expectOne((r) => r.url === `${API}/admin/orders/failed`).flush(page([]));
    });
  });

  it('InventoryApi', () => {
    const api = TestBed.inject(InventoryApi);
    api.products({ q: 'wid', inStock: true, page: 0, size: 20 }).subscribe();
    const p = mock.expectOne((r) => r.url === `${API}/products`);
    expect(p.request.params.get('inStock')).toBe('true');
    p.flush(page([makeProduct()]));
    api.adjustStock('p1', -5, 'damaged').subscribe();
    const s = mock.expectOne(`${API}/inventory/p1/stock`);
    expect(s.request.method).toBe('PUT');
    expect(s.request.body).toEqual({ delta: -5, reason: 'damaged' });
    s.flush(makeProduct());
    api.inventory({ lowStock: true }).subscribe();
    expect(mock.expectOne((r) => r.url === `${API}/inventory`).request.params.get('lowStock')).toBe('true');
    api.reservations({ status: 'RESERVED' }).subscribe();
    mock.expectOne((r) => r.url === `${API}/inventory/reservations`).flush(page([]));
  });

  it('Fulfillment, Notification and Ops APIs use the gateway rewrite paths', () => {
    TestBed.inject(FulfillmentApi).retry('f1').subscribe();
    mock.expectOne(`${API}/admin/fulfillments/f1/retry`).flush({});
    TestBed.inject(NotificationApi).retry('n1').subscribe();
    mock.expectOne(`${API}/admin/notifications/n1/retry`).flush({});
    const ops = TestBed.inject(OpsApi);
    ops.messaging('inventory').subscribe();
    mock.expectOne(`${API}/ops/inventory/messaging`).flush({});
    ops.audit('order', { entityType: 'Order', entityId: 'o1' }).subscribe();
    const a = mock.expectOne((r) => r.url === `${API}/ops/order/audit`);
    expect(a.request.params.get('entityId')).toBe('o1');
    a.flush(page([]));
    ops.health().subscribe();
    mock.expectOne(`${API}/ops/health`).flush({ status: 'UP', services: {} });
  });
});

import { HttpTestingController } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { SESSION_STORAGE_KEY } from '../core/auth/auth.store';
import { ConfigService } from '../core/config.service';
import { Role } from '../core/models';
import { CartStore } from '../core/state/cart.store';
import { API, httpTestProviders, makeOrder, makeProduct, makeSession, page } from '../testing/test-utils';
import { Catalog } from './catalog/catalog';
import { Dashboard } from './dashboard/dashboard';
import { FulfillmentView } from './fulfillment/fulfillment';
import { Notifications } from './notifications/notifications';
import { Audit } from './ops/audit';
import { FailedOrders } from './ops/failed-orders';
import { OpsDashboard } from './ops/ops-dashboard';
import { OrderDetail } from './orders/order-detail';
import { OrderList } from './orders/order-list';
import { Inventory } from './inventory/inventory';

const ID = '11111111-1111-1111-1111-111111111111';

function create<T>(cmp: Type<T>, roles: Role[], inputs: Record<string, unknown> = {}) {
  sessionStorage.clear();
  sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(makeSession({ roles, customerId: roles.includes('CUSTOMER') ? 'c1' : null })));
  TestBed.configureTestingModule({ imports: [cmp], providers: httpTestProviders() });
  TestBed.inject(ConfigService).set({ pollIntervalMs: 1000, pollMaxIntervalMs: 2000 });
  const mock = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<T> = TestBed.createComponent(cmp);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
  fixture.detectChanges();
  return { fixture, mock, el: fixture.nativeElement as HTMLElement };
}

const SAGA = { orderId: ID, sagaId: 's1', state: 'RUNNING', currentStep: 'CONFIRM_ORDER', attempts: 1, startedAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', lastError: null, steps: [{ step: 'VALIDATE', status: 'DONE', attempt: 1, detail: null, at: '2026-01-01T00:00:01Z' }] };

afterEach(() => sessionStorage.clear());

describe('Dashboard', () => {
  it('shows KPIs for staff and tolerates a failing source', () => {
    const { fixture, mock, el } = create(Dashboard, ['OPERATOR']);
    mock.expectOne((r) => r.url === `${API}/orders`).flush(page([makeOrder({ status: 'FULFILLED' }), makeOrder({ status: 'CREATED' }), makeOrder({ status: 'FAILED' })], 0, 100, 57));
    mock.expectOne((r) => r.url === `${API}/products`).flush(page([], 0, 1, 19));
    mock.expectOne((r) => r.url === `${API}/inventory`).flush(page([], 0, 1, 4));
    mock.expectOne((r) => r.url === `${API}/admin/orders/failed`).flush(page([], 0, 1, 2));
    mock.expectOne(`${API}/ops/health`).flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="kpi-total"]')?.textContent).toBe('57');
    expect(el.querySelector('[data-testid="kpi-low"]')?.textContent).toBe('4');
    expect(el.querySelector('[data-testid="kpi-failed"]')?.textContent).toBe('2');
    expect(el.textContent).toContain('Platform health');
    expect(el.textContent).toContain('n/a');
    expect(el.querySelectorAll('table.recent tbody tr').length).toBe(3);
  });

  it('customers do not request staff-only data', () => {
    const { fixture, mock, el } = create(Dashboard, ['CUSTOMER']);
    mock.expectOne((r) => r.url === `${API}/orders`).flush(page([]));
    mock.expectOne((r) => r.url === `${API}/products`).flush(page([], 0, 1, 3));
    mock.expectNone((r) => r.url.includes('/inventory') || r.url.includes('/admin/') || r.url.includes('/ops/'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="kpi-low"]')).toBeNull();
    expect(el.textContent).toContain('No orders yet');
  });
});

describe('Catalog', () => {
  it('lists products, adds to cart, disables out-of-stock and sends debounced filters', fakeAsync(() => {
    const { fixture, mock, el } = create(Catalog, ['CUSTOMER']);
    mock.expectOne((r) => r.url === `${API}/products`).flush(page([makeProduct({ id: 'a', sku: 'SKU-1001', available: 5 }), makeProduct({ id: 'b', sku: 'SKU-1020', name: 'Empty', available: 0 })]));
    fixture.detectChanges();
    const buttons = Array.from(el.querySelectorAll<HTMLButtonElement>('td button'));
    expect(buttons.length).toBe(2);
    expect(buttons[1].disabled).toBeTrue();
    buttons[0].click();
    expect(TestBed.inject(CartStore).count()).toBe(1);

    const q = el.querySelector<HTMLInputElement>('input[type="search"]')!;
    q.value = 'widget';
    q.dispatchEvent(new Event('input'));
    tick(299);
    mock.expectNone((r) => r.url === `${API}/products`);
    tick(1);
    const req = mock.expectOne((r) => r.url === `${API}/products`);
    expect(req.request.params.get('q')).toBe('widget');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('No products match');
    discardPeriodicTasks();
  }));

  it('shows an error banner with retry on failure', fakeAsync(() => {
    const { fixture, mock, el } = create(Catalog, ['CUSTOMER']);
    mock.expectOne((r) => r.url === `${API}/products`).flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Something went wrong');
    el.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    mock.expectOne((r) => r.url === `${API}/products`).flush(page([makeProduct()]));
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.textContent).toContain('SKU-1001');
  }));
});

describe('OrderList', () => {
  it('applies status and date filters (page reset) and validates the date range', fakeAsync(() => {
    const { fixture, mock, el } = create(OrderList, ['CUSTOMER']);
    mock.expectOne((r) => r.url === `${API}/orders`).flush(page([makeOrder()], 0, 20, 45));
    fixture.detectChanges();
    expect(el.textContent).toContain('11111111');
    const cmp = fixture.componentInstance as unknown as { form: { patchValue(v: object): void } };
    cmp.form.patchValue({ status: 'FULFILLED', from: '2026-01-02', to: '2026-01-01' }); // invalid range
    tick(300);
    mock.expectNone((r) => r.url === `${API}/orders`);
    cmp.form.patchValue({ to: '2026-01-31' });
    tick(300);
    const req = mock.expectOne((r) => r.url === `${API}/orders`);
    expect(req.request.params.get('status')).toBe('FULFILLED');
    expect(req.request.params.get('from')).toBe('2026-01-02T00:00:00Z');
    expect(req.request.params.get('to')).toBe('2026-01-31T23:59:59Z');
    expect(req.request.params.get('sort')).toBe('createdAt,desc');
    req.flush(page([]));
    discardPeriodicTasks();
  }));
});

describe('OrderDetail', () => {
  const flushDetail = (mock: HttpTestingController, status: 'CONFIRMED' | 'FULFILLED' | 'CANCELLED', staff = true) => {
    mock.expectOne(`${API}/orders/${ID}`).flush(makeOrder({ id: ID, status, statusReason: status === 'CANCELLED' ? 'Cancelled by user' : null }));
    mock.expectOne(`${API}/orders/${ID}/history`).flush([
      { fromStatus: null, toStatus: 'CREATED', reason: null, actor: 'customer1', occurredAt: '2026-01-01T00:00:00Z' },
    ]);
    if (staff) mock.expectOne(`${API}/admin/saga/${ID}`).flush(SAGA);
  };

  it('renders summary, timeline, history and (for staff) the saga; offers cancel for cancellable orders', () => {
    const { fixture, mock, el } = create(OrderDetail, ['OPERATOR'], { id: ID });
    flushDetail(mock, 'CONFIRMED');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('CONFIRMED');
    expect(el.querySelector('app-status-timeline ol')).not.toBeNull();
    expect(el.textContent).toContain('Saga (staff view)');
    expect(el.textContent).toContain('CONFIRM_ORDER');
    expect(el.querySelector('[data-testid="cancel-order"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="polling-indicator"]')).not.toBeNull();
    expect(el.querySelector('[role="status"][data-testid="status-announcer"]')?.textContent).toContain('CONFIRMED');
    fixture.destroy(); // stops polling
  });

  it('hides saga for customers and cancel for terminal orders; polling indicator disappears', () => {
    const { fixture, mock, el } = create(OrderDetail, ['CUSTOMER'], { id: ID });
    flushDetail(mock, 'FULFILLED', false);
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Saga (staff view)');
    expect(el.querySelector('[data-testid="cancel-order"]')).toBeNull();
    expect(el.querySelector('[data-testid="polling-indicator"]')).toBeNull();
  });

  it('cancels with a reason after confirmation and refreshes', () => {
    const { fixture, mock, el } = create(OrderDetail, ['CUSTOMER'], { id: ID });
    flushDetail(mock, 'CONFIRMED', false);
    fixture.detectChanges();
    const open = spyOn(TestBed.inject(MatDialog), 'open').and.returnValue({ afterClosed: () => of({ reason: 'changed my mind' }) } as never);
    el.querySelector<HTMLButtonElement>('[data-testid="cancel-order"]')!.click();
    expect(open).toHaveBeenCalled();
    const req = mock.expectOne(`${API}/orders/${ID}/cancel`);
    expect(req.request.body).toEqual({ reason: 'changed my mind' });
    req.flush(makeOrder({ id: ID, status: 'CANCELLED' }));
    flushDetail(mock, 'CANCELLED', false);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('CANCELLED');
    expect(el.querySelector('[data-testid="cancel-order"]')).toBeNull();
  });

  it('does nothing when the confirm dialog is dismissed', () => {
    const { fixture, mock, el } = create(OrderDetail, ['CUSTOMER'], { id: ID });
    flushDetail(mock, 'CONFIRMED', false);
    fixture.detectChanges();
    spyOn(TestBed.inject(MatDialog), 'open').and.returnValue({ afterClosed: () => of(undefined) } as never);
    el.querySelector<HTMLButtonElement>('[data-testid="cancel-order"]')!.click();
    mock.expectNone(`${API}/orders/${ID}/cancel`);
    fixture.destroy();
  });

  it('shows the invalid-state error from the API when cancel is rejected', () => {
    const { fixture, mock, el } = create(OrderDetail, ['CUSTOMER'], { id: ID });
    flushDetail(mock, 'CONFIRMED', false);
    fixture.detectChanges();
    spyOn(TestBed.inject(MatDialog), 'open').and.returnValue({ afterClosed: () => of({ reason: 'late' }) } as never);
    el.querySelector<HTMLButtonElement>('[data-testid="cancel-order"]')!.click();
    mock.expectOne(`${API}/orders/${ID}/cancel`).flush({ code: 'INVALID_STATE_TRANSITION' }, { status: 409, statusText: 'Conflict' });
    flushDetail(mock, 'FULFILLED', false);
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('not possible in the current state');
  });

  it('shows a not-found error for another customers order (404)', () => {
    const { fixture, mock, el } = create(OrderDetail, ['CUSTOMER'], { id: ID });
    mock.expectOne(`${API}/orders/${ID}`).flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    mock.match(`${API}/orders/${ID}/history`);
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('could not find');
    expect(el.querySelector('[data-testid="order-status"]')).toBeNull();
  });
});

describe('OpsDashboard', () => {
  it('renders health and per-service messaging status, flags DLT and unavailable lag', fakeAsync(() => {
    const { fixture, mock, el } = create(OpsDashboard, ['OPERATOR']);
    tick(0);
    const msg = (service: string, extra: object = {}) => ({
      service, consumerGroup: `${service}-service`, topics: [{ topic: 'oms.order.events.v1', partitions: 3, consumerLag: 4, committedOffsets: {} }],
      processedEvents: 10, duplicatesIgnored: 1, retries: 2, deadLettered: 0, outboxPending: 0, lastProcessedAt: '2026-01-01T00:00:00Z', dltTopics: [], ...extra,
    });
    mock.expectOne(`${API}/ops/order/messaging`).flush(msg('order'));
    mock.expectOne(`${API}/ops/inventory/messaging`).flush(msg('inventory', { deadLettered: 3, lagAvailable: false }));
    mock.expectOne(`${API}/ops/fulfillment/messaging`).flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'x' });
    mock.expectOne(`${API}/ops/notification/messaging`).flush(msg('notification'));
    mock.expectOne(`${API}/ops/health`).flush({ status: 'DEGRADED', services: { order: { status: 'UP' }, fulfillment: { status: 'DOWN' } } });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="health-overall"]')?.textContent).toContain('DEGRADED');
    expect(el.querySelector('[data-testid="svc-card-order"] [data-testid="lag"]')?.textContent).toBe('4');
    expect(el.querySelector('[data-testid="svc-card-inventory"] [data-testid="lag"]')?.textContent).toContain('n/a');
    expect(el.querySelector('[data-testid="svc-card-inventory"] [data-testid="dlt"]')?.textContent).toContain('needs attention');
    expect(el.querySelector('[data-testid="svc-card-fulfillment"] [role="alert"]')?.textContent).toContain('Something went wrong');
    discardPeriodicTasks();
  }));
});

describe('FailedOrders', () => {
  const flushList = (mock: HttpTestingController) =>
    mock.expectOne((r) => r.url === `${API}/admin/orders/failed`).flush(page([makeOrder({ id: ID, status: 'FAILED', statusReason: 'Fulfillment failed: FAILTOWN' })]));

  it('lets an operator investigate and retry; reconciliation is not offered', () => {
    const { fixture, mock, el } = create(FailedOrders, ['OPERATOR']);
    flushList(mock);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="run-reconciliation"]')).toBeNull();
    expect(el.textContent).toContain('Select an order above');
    Array.from(el.querySelectorAll<HTMLButtonElement>('td button')).find((b) => b.textContent?.includes('Investigate'))!.click();
    mock.expectOne(`${API}/orders/${ID}`).flush(makeOrder({ id: ID, status: 'FAILED' }));
    mock.expectOne(`${API}/orders/${ID}/history`).flush([]);
    mock.expectOne(`${API}/admin/saga/${ID}`).flush(SAGA);
    mock.expectOne((r) => r.url === `${API}/ops/order/audit`).flush(page([{ id: 'a1', entityType: 'Order', entityId: ID, action: 'ORDER_FAILED', actor: 'system', correlationId: null, details: 'x', occurredAt: '2026-01-01T00:00:00Z' }]));
    fixture.detectChanges();
    expect(el.textContent).toContain('ORDER_FAILED');
    expect(el.textContent).toContain('CONFIRM_ORDER');

    spyOn(TestBed.inject(MatDialog), 'open').and.returnValue({ afterClosed: () => of({ reason: '' }) } as never);
    el.querySelector<HTMLButtonElement>('[data-testid="retry-order"]')!.click();
    mock.expectOne(`${API}/admin/orders/${ID}/retry`).flush({});
    // reload of the panel and the list
    mock.expectOne(`${API}/orders/${ID}`).flush(makeOrder({ id: ID, status: 'CONFIRMED' }));
    mock.expectOne(`${API}/orders/${ID}/history`).flush([]);
    mock.expectOne(`${API}/admin/saga/${ID}`).flush(SAGA);
    mock.expectOne((r) => r.url === `${API}/ops/order/audit`).flush(page([]));
    flushList(mock);
  });

  it('lets an admin run reconciliation and shows the result', () => {
    const { fixture, mock, el } = create(FailedOrders, ['ADMIN']);
    flushList(mock);
    fixture.detectChanges();
    spyOn(TestBed.inject(MatDialog), 'open').and.returnValue({ afterClosed: () => of({ reason: '' }) } as never);
    el.querySelector<HTMLButtonElement>('[data-testid="run-reconciliation"]')!.click();
    mock.expectOne(`${API}/admin/reconciliation/run`).flush({ examined: 12, repaired: 2, details: ['Released orphan reservation r-1'] });
    flushList(mock);
    fixture.detectChanges();
    const out = el.querySelector('[data-testid="recon-result"]')?.textContent ?? '';
    expect(out).toContain('12');
    expect(out).toContain('Released orphan reservation r-1');
  });

  it('shows the empty state when nothing failed', () => {
    const { fixture, mock, el } = create(FailedOrders, ['ADMIN']);
    mock.expectOne((r) => r.url === `${API}/admin/orders/failed`).flush(page([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('No failed orders');
  });
});

describe('Audit, Notifications, Fulfillment, Inventory', () => {
  it('Audit queries the selected service with entity filters', fakeAsync(() => {
    const { fixture, mock } = create(Audit, ['OPERATOR']);
    mock.expectOne((r) => r.url === `${API}/ops/order/audit`).flush(page([]));
    const cmp = fixture.componentInstance as unknown as { form: { patchValue(v: object): void } };
    cmp.form.patchValue({ service: 'inventory', entityType: 'Product', entityId: 'p1' });
    tick(300);
    const req = mock.expectOne((r) => r.url === `${API}/ops/inventory/audit`);
    expect(req.request.params.get('entityType')).toBe('Product');
    expect(req.request.params.get('entityId')).toBe('p1');
    req.flush(page([]));
    discardPeriodicTasks();
  }));

  it('Notifications: staff see retry for FAILED rows only; customers do not', () => {
    const rows = [
      { id: 'n1aaaaaaaa', customerId: 'c', orderId: ID, channel: 'EMAIL', template: 't', subject: 'Order confirmed', status: 'FAILED', attempts: 3, lastError: 'smtp', correlationId: null, sourceEventId: null, createdAt: '2026-01-01T00:00:00Z', sentAt: null },
      { id: 'n2bbbbbbbb', customerId: 'c', orderId: ID, channel: 'EMAIL', template: 't', subject: 'Shipped', status: 'SENT', attempts: 1, lastError: null, correlationId: null, sourceEventId: null, createdAt: '2026-01-01T00:00:00Z', sentAt: '2026-01-01T00:00:01Z' },
    ];
    const staff = create(Notifications, ['OPERATOR']);
    staff.mock.expectOne((r) => r.url === `${API}/notifications`).flush(page(rows));
    staff.fixture.detectChanges();
    expect(staff.el.querySelectorAll('td button').length).toBe(1);
    staff.el.querySelector<HTMLButtonElement>('td button')!.click();
    staff.mock.expectOne(`${API}/admin/notifications/n1aaaaaaaa/retry`).flush({});
    staff.mock.expectOne((r) => r.url === `${API}/notifications`).flush(page(rows));
  });

  it('Notifications for customers show no action column', () => {
    TestBed.resetTestingModule();
    const c = create(Notifications, ['CUSTOMER']);
    c.mock.expectOne((r) => r.url === `${API}/notifications`).flush(page([]));
    c.fixture.detectChanges();
    expect(c.el.textContent).toContain('No notifications');
    expect(c.el.querySelector('td button')).toBeNull();
  });

  it('Fulfillment lists with retry on FAILED', () => {
    const { fixture, mock, el } = create(FulfillmentView, ['OPERATOR']);
    mock
      .expectOne((r) => r.url === `${API}/fulfillments`)
      .flush(page([{ id: 'f1aaaaaaaa', orderId: ID, status: 'FAILED', trackingNumber: null, attempts: 3, failureReason: 'FAILTOWN unreachable', events: [], createdAt: '', updatedAt: '2026-01-01T00:00:00Z' }]));
    fixture.detectChanges();
    expect(el.textContent).toContain('FAILTOWN unreachable');
    el.querySelector<HTMLButtonElement>('td button')!.click();
    mock.expectOne(`${API}/admin/fulfillments/f1aaaaaaaa/retry`).flush({});
    mock.expectOne((r) => r.url === `${API}/fulfillments`).flush(page([]));
  });

  it('Inventory: low-stock filter and stock table with adjust action', fakeAsync(() => {
    const { fixture, mock, el } = create(Inventory, ['OPERATOR']);
    mock.expectOne((r) => r.url === `${API}/inventory`).flush(page([makeProduct({ onHand: 3, reserved: 1, available: 2 })]));
    mock.expectOne((r) => r.url === `${API}/inventory/reservations`).flush(page([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('SKU-1001');
    const cmp = fixture.componentInstance as unknown as { stockForm: { patchValue(v: object): void } };
    cmp.stockForm.patchValue({ lowStock: true });
    tick(300);
    const req = mock.expectOne((r) => r.url === `${API}/inventory`);
    expect(req.request.params.get('lowStock')).toBe('true');
    req.flush(page([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('No stock records match');
    discardPeriodicTasks();
  }));
});

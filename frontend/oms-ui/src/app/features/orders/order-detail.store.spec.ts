import { HttpTestingController } from '@angular/common/http/testing';
import { fakeAsync, TestBed, tick, discardPeriodicTasks } from '@angular/core/testing';
import { SESSION_STORAGE_KEY } from '../../core/auth/auth.store';
import { ConfigService } from '../../core/config.service';
import { Role, OrderStatus } from '../../core/models';
import { API, httpTestProviders, makeOrder, makeSession } from '../../testing/test-utils';
import { OrderDetailStore, canCancel, isTerminal } from './order-detail.store';

const ID = '11111111-1111-1111-1111-111111111111';

describe('order detail helpers', () => {
  it('isTerminal / canCancel follow the contract state machine', () => {
    const terminal: OrderStatus[] = ['FULFILLED', 'REJECTED', 'CANCELLED', 'FAILED'];
    const cancellable: OrderStatus[] = ['CREATED', 'VALIDATED', 'INVENTORY_RESERVED', 'CONFIRMED'];
    for (const s of terminal) {
      expect(isTerminal(makeOrder({ status: s }))).toBeTrue();
      expect(canCancel(makeOrder({ status: s }))).toBeFalse();
    }
    for (const s of cancellable) {
      expect(isTerminal(makeOrder({ status: s }))).toBeFalse();
      expect(canCancel(makeOrder({ status: s }))).toBeTrue();
    }
    expect(canCancel(makeOrder({ status: 'COMPENSATION_PENDING' }))).toBeFalse();
    expect(isTerminal(null)).toBeFalse();
  });
});

describe('OrderDetailStore polling', () => {
  let mock: HttpTestingController;
  let store: OrderDetailStore;

  function setup(roles: Role[]) {
    sessionStorage.clear();
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(makeSession({ roles })));
    TestBed.configureTestingModule({ providers: [httpTestProviders(), OrderDetailStore] });
    TestBed.inject(ConfigService).set({ pollIntervalMs: 1000, pollMaxIntervalMs: 4000 });
    mock = TestBed.inject(HttpTestingController);
    store = TestBed.inject(OrderDetailStore);
  }

  const flushRound = (status: OrderStatus, staff: boolean) => {
    mock.expectOne(`${API}/orders/${ID}`).flush(makeOrder({ id: ID, status }));
    mock.expectOne(`${API}/orders/${ID}/history`).flush([]);
    if (staff) mock.expectOne(`${API}/admin/saga/${ID}`).flush({ orderId: ID, sagaId: 's', state: 'RUNNING', currentStep: 'X', attempts: 1, startedAt: '', updatedAt: '', lastError: null, steps: [] });
  };

  afterEach(() => {
    mock.verify();
    sessionStorage.clear();
  });

  it('polls with backoff until the order is terminal and then stops', fakeAsync(() => {
    setup(['OPERATOR']);
    store.start(ID);
    expect(store.loading()).toBeTrue();
    expect(store.polling()).toBeTrue();
    flushRound('CREATED', true);
    expect(store.order()?.status).toBe('CREATED');
    expect(store.saga()).not.toBeNull();
    expect(store.loading()).toBeFalse();
    expect(store.cancellable()).toBeTrue();

    tick(999);
    mock.expectNone(`${API}/orders/${ID}`);
    tick(1); // first delay 1000
    flushRound('CONFIRMED', true);

    tick(1500); // second delay 1500 (factor 1.5)
    flushRound('FULFILLED', true);
    expect(store.terminal()).toBeTrue();
    expect(store.polling()).toBeFalse();
    expect(store.cancellable()).toBeFalse();

    tick(60_000);
    mock.expectNone(`${API}/orders/${ID}`);
  }));

  it('does not request the saga for customers', fakeAsync(() => {
    setup(['CUSTOMER']);
    store.start(ID);
    flushRound('FULFILLED', false);
    expect(store.saga()).toBeNull();
    expect(store.polling()).toBeFalse();
  }));

  it('stopPolling cancels pending timers (takeUntil)', fakeAsync(() => {
    setup(['CUSTOMER']);
    store.start(ID);
    flushRound('CREATED', false);
    store.stopPolling();
    tick(30_000);
    mock.expectNone(`${API}/orders/${ID}`);
    expect(store.polling()).toBeFalse();
    discardPeriodicTasks();
  }));

  it('stops polling and exposes the error when a poll fails', fakeAsync(() => {
    setup(['CUSTOMER']);
    store.start(ID);
    mock.expectOne(`${API}/orders/${ID}`).flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    // forkJoin cancels the sibling request
    mock.match(`${API}/orders/${ID}/history`);
    expect(store.error()?.code).toBe('NOT_FOUND');
    expect(store.polling()).toBeFalse();
    tick(30_000);
    mock.expectNone(`${API}/orders/${ID}`);
  }));

  it('refresh restarts polling for a non-terminal order', fakeAsync(() => {
    setup(['CUSTOMER']);
    store.start(ID);
    flushRound('CREATED', false);
    store.refresh(ID);
    flushRound('VALIDATED', false);
    expect(store.order()?.status).toBe('VALIDATED');
    expect(store.polling()).toBeTrue();
    store.stopPolling();
  }));
});

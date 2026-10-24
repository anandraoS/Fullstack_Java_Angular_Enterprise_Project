import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SESSION_STORAGE_KEY } from '../../core/auth/auth.store';
import { API, httpTestProviders, makeOrder, makeSession } from '../../testing/test-utils';
import { OrderDetail } from './order-detail';

/**
 * Regression (integration defect found by the live Playwright run): an order rejected at validation comes back from the real
 * backend with sku/name/unitPrice/lineTotal = null (contract section 13). The template called `unitPrice.toFixed()` and threw,
 * which left the whole order page blank (status chip empty, endless spinner).
 */
describe('OrderDetail component with an unpriced / rejected order', () => {
  let fixture: ComponentFixture<OrderDetail>;
  let mock: HttpTestingController;
  const ID = '41f8ba99-b5ed-4563-9756-c683fac3ff6a';

  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(makeSession({ roles: ['CUSTOMER'], username: 'customer1', customerId: 'c0000000-0000-0000-0000-000000000001' })));
    TestBed.configureTestingModule({ imports: [OrderDetail], providers: httpTestProviders() });
    mock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OrderDetail);
    fixture.componentRef.setInput('id', ID);
    fixture.detectChanges();
  });

  afterEach(() => {
    mock.match(() => true); // drop pending polls
    sessionStorage.clear();
  });

  it('renders status, reason and a placeholder for null prices instead of throwing', () => {
    const rejected = makeOrder({
      id: ID,
      status: 'REJECTED',
      statusReason: 'Validation failed: SKU-1020: INSUFFICIENT_STOCK',
      totalAmount: 0,
      items: [{ productId: 'a0000000-0000-0000-0000-000000001020', sku: null, name: null, quantity: 1, unitPrice: null, lineTotal: null }],
    });
    mock.expectOne(`${API}/orders/${ID}`).flush(rejected);
    mock.expectOne(`${API}/orders/${ID}/history`).flush([]);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('REJECTED');
    expect(el.querySelector('[data-testid="status-reason"]')?.textContent).toContain('INSUFFICIENT_STOCK');
    const cells = Array.from(el.querySelectorAll('table.t tbody tr:first-child td')).map((c) => c.textContent?.trim());
    expect(cells.slice(-2)).toEqual(['-', '-']);
  });
});

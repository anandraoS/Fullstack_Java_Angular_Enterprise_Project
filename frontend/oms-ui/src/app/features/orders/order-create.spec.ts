import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { SESSION_STORAGE_KEY } from '../../core/auth/auth.store';
import { Role } from '../../core/models';
import { CartStore } from '../../core/state/cart.store';
import { API, httpTestProviders, makeOrder, makeProduct, makeSession, page } from '../../testing/test-utils';
import { isValidIdempotencyKey } from './order-form';
import { OrderCreate } from './order-create';

const CUSTOMER_ID = 'c0000000-0000-0000-0000-000000000001';
const PRODUCTS = [
  makeProduct({ id: 'p1', sku: 'SKU-1001', name: 'Widget', price: 10, available: 500 }),
  makeProduct({ id: 'p2', sku: 'SKU-1002', name: 'Gadget', price: 2.5, available: 50 }),
];

describe('OrderCreate component', () => {
  let fixture: ComponentFixture<OrderCreate>;
  let cmp: OrderCreate;
  let mock: HttpTestingController;
  let router: Router;

  function setup(roles: Role[], opts: { fromCart?: boolean } = {}) {
    sessionStorage.clear();
    sessionStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify(makeSession({ roles, username: roles.includes('CUSTOMER') ? 'customer1' : 'operator1', customerId: roles.includes('CUSTOMER') ? CUSTOMER_ID : null })),
    );
    TestBed.configureTestingModule({ imports: [OrderCreate], providers: httpTestProviders() });
    mock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    if (opts.fromCart) {
      TestBed.inject(CartStore).add({ id: 'p2', sku: 'SKU-1002', name: 'Gadget', price: 2.5 }, 4);
    }
    fixture = TestBed.createComponent(OrderCreate);
    if (opts.fromCart) fixture.componentRef.setInput('fromCart', '1');
    cmp = fixture.componentInstance;
    fixture.detectChanges();
    if (!roles.includes('CUSTOMER')) {
      mock.expectOne((r) => r.url === `${API}/customers`).flush(page([{ id: CUSTOMER_ID, name: 'Acme', email: 'a@acme.example.test' }]));
    }
    mock.expectOne((r) => r.url === `${API}/products`).flush(page(PRODUCTS));
    fixture.detectChanges();
  }

  function fillValid(): void {
    cmp.form.patchValue({ customerId: CUSTOMER_ID });
    cmp.items.at(0).patchValue({ productId: 'p1', quantity: 3 });
    cmp.form.controls.shippingAddress.patchValue({ line1: '1 Main St', city: 'Springfield', postalCode: '12345', country: 'us' });
    fixture.detectChanges();
  }

  const posts = () => mock.match((r) => r.method === 'POST' && r.url === `${API}/orders`);
  const flushAccepted = (req: TestRequest, id = 'o-new', headers: Record<string, string> = {}) =>
    req.flush(makeOrder({ id }), { status: 202, statusText: 'Accepted', headers });

  afterEach(() => {
    mock.verify();
    sessionStorage.clear();
  });

  it('for a customer the customer is fixed from the session and not selectable', () => {
    setup(['CUSTOMER']);
    expect(cmp.form.controls.customerId.disabled).toBeTrue();
    expect(cmp.form.getRawValue().customerId).toBe(CUSTOMER_ID);
    expect(fixture.nativeElement.querySelector('[data-testid="customer-select"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('customer1');
  });

  it('for staff a customer must be selected', () => {
    setup(['OPERATOR']);
    expect(fixture.nativeElement.querySelector('[data-testid="customer-select"]')).not.toBeNull();
    expect(cmp.form.controls.customerId.hasError('required')).toBeTrue();
  });

  it('blocks an invalid submit, announces it and sends nothing', () => {
    setup(['OPERATOR']);
    cmp.submit();
    fixture.detectChanges();
    expect(posts().length).toBe(0);
    expect(fixture.nativeElement.querySelector('p.summary[role="alert"]')?.textContent).toContain('fix the highlighted fields');
    expect(cmp.form.controls.shippingAddress.get('line1')?.touched).toBeTrue();
  });

  it('validates quantity range, postal code and country', () => {
    setup(['OPERATOR']);
    fillValid();
    expect(cmp.form.valid).toBeTrue();
    cmp.items.at(0).patchValue({ quantity: 0 });
    expect(cmp.form.valid).toBeFalse();
    cmp.items.at(0).patchValue({ quantity: 1001 });
    expect(cmp.items.at(0).controls.quantity.hasError('max')).toBeTrue();
    cmp.items.at(0).patchValue({ quantity: 1.5 });
    expect(cmp.items.at(0).controls.quantity.hasError('integer')).toBeTrue();
    cmp.items.at(0).patchValue({ quantity: 2 });
    cmp.form.controls.shippingAddress.patchValue({ country: 'USA' });
    expect(cmp.form.controls.shippingAddress.get('country')?.invalid).toBeTrue();
    cmp.form.controls.shippingAddress.patchValue({ country: 'US', postalCode: '!' });
    expect(cmp.form.controls.shippingAddress.get('postalCode')?.invalid).toBeTrue();
  });

  it('adds and removes item rows via the buttons; the last row cannot be removed', () => {
    setup(['OPERATOR']);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.item-row').length).toBe(1);
    expect(el.querySelector<HTMLButtonElement>('.item-row button')?.disabled).toBeTrue();
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes('Add item'))?.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('.item-row').length).toBe(2);
    expect(cmp.items.length).toBe(2);
    el.querySelector<HTMLButtonElement>('.item-row button[aria-label="Remove item 2"]')?.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('.item-row').length).toBe(1);
  });

  it('caps the number of lines at 50', () => {
    setup(['OPERATOR']);
    for (let i = 0; i < 60; i++) cmp.addRow();
    expect(cmp.items.length).toBe(50);
  });

  it('rejects the same product on two lines', () => {
    setup(['OPERATOR']);
    fillValid();
    cmp.addRow();
    cmp.items.at(1).patchValue({ productId: 'p1', quantity: 1 });
    fixture.detectChanges();
    expect(cmp.form.invalid).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('same product appears on more than one line');
    cmp.submit();
    expect(posts().length).toBe(0);
  });

  it('estimates the total from product prices', () => {
    setup(['OPERATOR']);
    fillValid(); // 3 x 10
    cmp.addRow();
    cmp.items.at(1).patchValue({ productId: 'p2', quantity: 4 }); // 4 x 2.50
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="est-total"]').textContent).toBe('40.00 USD');
  });

  it('pre-fills rows from the cart', () => {
    setup(['OPERATOR'], { fromCart: true });
    expect(cmp.items.length).toBe(1);
    expect(cmp.items.at(0).getRawValue()).toEqual({ productId: 'p2', quantity: 4 });
  });

  it('submits with a contract-valid Idempotency-Key, normalizes the body and navigates on 202', () => {
    setup(['OPERATOR']);
    fillValid();
    TestBed.inject(CartStore).add({ id: 'p1', sku: 'S', name: 'n', price: 1 });
    cmp.submit();
    const [req] = posts();
    const key = req.request.headers.get('Idempotency-Key') as string;
    expect(isValidIdempotencyKey(key)).toBeTrue();
    expect(req.request.body).toEqual({
      customerId: CUSTOMER_ID,
      items: [{ productId: 'p1', quantity: 3 }],
      shippingAddress: { line1: '1 Main St', city: 'Springfield', postalCode: '12345', country: 'US' },
    });
    flushAccepted(req, 'o-42');
    expect(router.navigate).toHaveBeenCalledWith(['/orders', 'o-42']);
    expect(TestBed.inject(CartStore).count()).toBe(0);
    expect(cmp.keyHolder.current).toBeNull();
  });

  it('ignores a second submit while the first is in flight (double-click safe)', () => {
    setup(['OPERATOR']);
    fillValid();
    cmp.submit();
    cmp.submit();
    cmp.submit();
    const sent = posts();
    expect(sent.length).toBe(1);
    flushAccepted(sent[0]);
  });

  it('reuses the SAME key when retrying after an unknown outcome, then a new key for a new order', () => {
    setup(['OPERATOR']);
    fillValid();
    cmp.submit();
    const first = posts()[0];
    const key1 = first.request.headers.get('Idempotency-Key');
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('safe to submit again');
    expect(cmp.submitting()).toBeFalse();

    cmp.submit();
    const second = posts()[0];
    expect(second.request.headers.get('Idempotency-Key')).toBe(key1);
    flushAccepted(second, 'o-1', { 'Idempotent-Replay': 'true' });

    // next, separate order gets a fresh key
    fixture.detectChanges();
    cmp.submit();
    const third = posts()[0];
    expect(third.request.headers.get('Idempotency-Key')).not.toBe(key1);
    flushAccepted(third);
  });

  it('after a changed body the key changes (avoids 422 IDEMPOTENCY_KEY_REUSED)', () => {
    setup(['OPERATOR']);
    fillValid();
    cmp.submit();
    const first = posts()[0];
    const key1 = first.request.headers.get('Idempotency-Key');
    first.flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'err' });
    cmp.items.at(0).patchValue({ quantity: 7 });
    cmp.submit();
    const second = posts()[0];
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key1);
    flushAccepted(second);
  });

  it('maps server validation errors onto fields and does not keep the key', () => {
    setup(['OPERATOR']);
    fillValid();
    cmp.submit();
    const req = posts()[0];
    req.flush(
      { code: 'VALIDATION_FAILED', errors: [{ field: 'shippingAddress.city', message: 'unsupported city' }, { field: 'items[0].quantity', message: 'too many' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(cmp.form.get('shippingAddress.city')?.errors?.['server']).toBe('unsupported city');
    expect(cmp.form.get('items.0.quantity')?.errors?.['server']).toBe('too many');
    expect(cmp.keyHolder.current).toBeNull();
    expect(cmp.submitting()).toBeFalse();
  });

  it('shows the friendly message for a 429 without keeping the form locked', () => {
    setup(['OPERATOR']);
    fillValid();
    cmp.submit();
    posts()[0].flush({ code: 'RATE_LIMITED' }, { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Too many requests');
    expect(cmp.submitting()).toBeFalse();
  });
});

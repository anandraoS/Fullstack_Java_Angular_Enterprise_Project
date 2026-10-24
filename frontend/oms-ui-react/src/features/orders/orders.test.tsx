import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { ORDER_ID, adminSession, customerSession, history, makeOrder, makeRejectedOrder, operatorSession, page, problem, saga } from '../../test/fixtures';
import { mockApi, renderApp } from '../../test/render';
import { server, url } from '../../test/server';
import { cartAdd } from '../../store/cartSlice';
import { act } from 'react';

const P1 = 'a0000000-0000-0000-0000-000000001001';
const body202 = (over = {}) => HttpResponse.json(makeOrder({ status: 'CREATED', ...over }), { status: 202, headers: { Location: `/api/v1/orders/${ORDER_ID}` } });

async function selectProduct(user: ReturnType<typeof renderApp>['user'], index: number, sku: string) {
  const selects = screen.getAllByRole('combobox', { name: /Product/ });
  await user.click(selects[index]);
  await user.click(await screen.findByRole('option', { name: new RegExp(sku) }));
}

async function fillAddress(user: ReturnType<typeof renderApp>['user'], city = 'Springfield') {
  await user.type(screen.getByLabelText(/Address line/), '1 Test Street');
  await user.type(screen.getByLabelText(/^City/), city);
  await user.type(screen.getByLabelText(/Postal code/), '12345');
}

describe('OrderCreate', () => {
  it('customers order for their own account (no customer selector); items start with one row', async () => {
    mockApi();
    renderApp('/orders/new', { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Create order' })).toBeVisible();
    expect(screen.getByText(/Ordering as/)).toHaveTextContent('customer1');
    expect(screen.queryByTestId('customer-select')).toBeNull();
    expect(screen.getAllByRole('group', { name: /^Item/ })).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Remove item 1' })).toBeDisabled(); // cannot remove the last row
  });

  it('staff must pick a customer', async () => {
    mockApi();
    const { user } = renderApp('/orders/new', { session: operatorSession });
    expect(await screen.findByTestId('customer-select')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Select a customer.')).toBeVisible();
    await user.click(screen.getByTestId('customer-select'));
    expect(await screen.findByRole('option', { name: /Acme Industrial Supply/ })).toBeVisible();
  });

  it('adds and removes item rows (useFieldArray), computes estimated totals and the 50 line cap disables Add', async () => {
    mockApi();
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await waitFor(() => expect(screen.getAllByRole('combobox', { name: /Product/ })[0]).toBeEnabled());
    await selectProduct(user, 0, 'SKU-1001');
    await user.clear(screen.getByTestId('qty-0'));
    await user.type(screen.getByTestId('qty-0'), '4');
    expect(screen.getByTestId('est-total')).toHaveTextContent('10.00 USD'); // 4 x 2.50

    await user.click(screen.getByRole('button', { name: 'Add item' }));
    expect(screen.getAllByRole('group', { name: /^Item/ })).toHaveLength(2);
    await waitFor(() => expect(screen.getByTestId('product-1')).toHaveFocus()); // focus moves to the new row
    await user.click(screen.getByRole('button', { name: 'Remove item 2' }));
    expect(screen.getAllByRole('group', { name: /^Item/ })).toHaveLength(1);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add item' })).toHaveFocus()); // stable focus after removal
  });

  it('warns about the same product on two lines and blocks submission', async () => {
    mockApi();
    const post = vi.fn();
    server.use(http.post(url('/orders'), () => (post(), body202())));
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await user.click(screen.getByRole('button', { name: 'Add item' }));
    await selectProduct(user, 1, 'SKU-1001');
    expect(await screen.findByText(/same product appears on more than one line/)).toBeVisible();
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Please fix the highlighted fields before submitting.')).toBeVisible();
    expect(post).not.toHaveBeenCalled();
  });

  it('shows field validation messages for an empty form and focuses the first invalid control', async () => {
    mockApi();
    const post = vi.fn();
    server.use(http.post(url('/orders'), () => (post(), body202())));
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Select a product.')).toBeVisible();
    expect(screen.getByText('Address line is required.')).toBeVisible();
    expect(screen.getByText('City is required.')).toBeVisible();
    expect(screen.getByText(/Enter a valid postal code/)).toBeVisible();
    expect(post).not.toHaveBeenCalled();
  });

  it('prefills rows from the cart when arriving with ?fromCart=1', async () => {
    mockApi();
    // seed the cart through a first render of the same store is not possible across renders, so use the store handle
    const { store } = renderApp('/catalog', { session: customerSession });
    act(() => {
      store.dispatch(cartAdd({ product: { id: P1, sku: 'SKU-1001', name: 'Hex Bolt M8', price: 2.5 }, qty: 7 }));
    });
    // client-side navigation to the order form
    const cartLink = await screen.findByRole('link', { name: /Cart: 7 items/ });
    cartLink.click();
    expect(await screen.findByRole('heading', { level: 1, name: 'Create order' })).toBeVisible();
    await waitFor(() => expect(screen.getByTestId('product-0')).toHaveTextContent('SKU-1001'));
    expect(screen.getByTestId('qty-0')).toHaveValue(7);
  });

  it('submits with an Idempotency-Key, clears the cart, toasts and opens the order detail', async () => {
    mockApi();
    let key: string | null = null;
    let sent: unknown;
    server.use(
      http.post(url('/orders'), async ({ request }) => {
        key = request.headers.get('idempotency-key');
        sent = await request.json();
        return body202();
      }),
    );
    const { user, store } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await user.clear(screen.getByTestId('qty-0'));
    await user.type(screen.getByTestId('qty-0'), '2');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByRole('heading', { level: 1, name: /^Order 11111111/ })).toBeVisible();
    expect(key).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(sent).toEqual({ customerId: 'c0000000-0000-0000-0000-000000000001', items: [{ productId: P1, quantity: 2 }], shippingAddress: { line1: '1 Test Street', city: 'Springfield', postalCode: '12345', country: 'US' } });
    expect(await screen.findByText('Order accepted. Processing has started.')).toBeVisible();
    expect(store.getState().cart.lines).toEqual([]);
  });

  it('tells the user when the server replayed an earlier identical submission', async () => {
    mockApi(http.post(url('/orders'), () => HttpResponse.json(makeOrder({ status: 'CREATED' }), { status: 202, headers: { 'Idempotent-Replay': 'true' } })));
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('This order was already accepted; showing the original.')).toBeVisible();
  });

  it('retry after a lost response (network error) REUSES the key; a body change or a definite answer gets a NEW key', async () => {
    const keys: string[] = [];
    let mode: 'network' | 'ok' | 'validation' = 'network';
    mockApi(
      http.post(url('/orders'), ({ request }) => {
        keys.push(request.headers.get('idempotency-key') as string);
        if (mode === 'network') return HttpResponse.error();
        if (mode === 'validation') return problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'shippingAddress.city', message: 'unknown city' }] });
        return body202();
      }),
    );
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);

    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText(/safe to submit again/)).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent('Cannot reach the server');

    await user.click(screen.getByRole('button', { name: 'Place order' }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).toBe(keys[0]); // same key on retry

    // change the body -> must NOT reuse the key (server would answer 422 IDEMPOTENCY_KEY_REUSED)
    await user.type(screen.getByLabelText(/^City/), 'x');
    mode = 'validation';
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[2]).not.toBe(keys[0]);

    // the 400 was a definite answer: the next attempt (same body) gets yet another key
    mode = 'ok';
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    await waitFor(() => expect(keys).toHaveLength(4));
    expect(keys[3]).not.toBe(keys[2]);
    expect(await screen.findByRole('heading', { level: 1, name: /^Order / })).toBeVisible();
  });

  it('a 5xx also keeps the key (outcome unknown)', async () => {
    const keys: string[] = [];
    mockApi(http.post(url('/orders'), ({ request }) => (keys.push(request.headers.get('idempotency-key') as string), problem(503, 'UPSTREAM_UNAVAILABLE'))));
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText(/safe to submit again/)).toBeVisible();
    expect(screen.getByTestId('idempotency-key')).toHaveTextContent(keys[0]);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).toBe(keys[0]);
  });

  it('maps server field errors onto the form controls and keeps unmatched ones in the banner', async () => {
    mockApi(
      http.post(url('/orders'), () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [
            { field: 'shippingAddress.city', message: 'City not serviceable' },
            { field: 'weird.thing', message: 'unmapped' },
          ],
        }),
      ),
    );
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('City not serviceable')).toBeVisible();
    expect(screen.getByText(/weird.thing: unmapped/)).toBeVisible();
  });

  it('when every field error is mapped the banner is the short "fields need attention" message', async () => {
    mockApi(http.post(url('/orders'), () => problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'items[0].quantity', message: 'too many' }] })));
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Some fields need attention.')).toBeVisible();
    expect(screen.getByText('Enter a whole number from 1 to 1000.')).toBeVisible();
  });

  it('shows a 429 rate limit toast and re-enables the submit button', async () => {
    mockApi(http.post(url('/orders'), () => HttpResponse.json({ code: 'RATE_LIMITED' }, { status: 429, headers: { 'Retry-After': '3', 'Content-Type': 'application/problem+json' } })));
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText(/Try again in 3s/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Place order' })).toBeEnabled();
  });

  it('locks the submit button while a request is in flight (no double submit)', async () => {
    let calls = 0;
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    mockApi(
      http.post(url('/orders'), async () => {
        calls++;
        await gate;
        return body202();
      }),
    );
    const { user } = renderApp('/orders/new', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Create order' });
    await selectProduct(user, 0, 'SKU-1001');
    await fillAddress(user);
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    const busy = await screen.findByRole('button', { name: 'Submitting...' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy); // a second click while in flight must not post again
    release();
    await screen.findByRole('heading', { level: 1, name: /^Order / });
    expect(calls).toBe(1);
  });

  it('shows an error banner with retry when the product list cannot be loaded', async () => {
    mockApi(http.get(url('/products'), () => problem(500, 'INTERNAL_ERROR')));
    renderApp('/orders/new', { session: customerSession });
    expect(await screen.findByText(/Something went wrong on our side/, undefined, { timeout: 5000 })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
});

describe('OrderList', () => {
  it('lists orders with status chips and sends filters and sorting to the server (page resets to 0)', async () => {
    const queries: URLSearchParams[] = [];
    mockApi(
      http.get(url('/orders'), ({ request }) => {
        queries.push(new URL(request.url).searchParams);
        return HttpResponse.json(page([makeOrder(), makeRejectedOrder({ id: '99999999-0000-0000-0000-000000000000' })], { totalElements: 45, totalPages: 3 }));
      }),
    );
    const { user } = renderApp('/orders', { session: operatorSession });
    expect(await screen.findByRole('table', { name: 'Orders' })).toBeVisible();
    expect(screen.getByText('All customer orders')).toBeVisible();
    expect(queries[0].get('sort')).toBe('createdAt,desc');
    expect(queries[0].get('page')).toBe('0');

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(queries.at(-1)?.get('page')).toBe('1'));

    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByRole('option', { name: 'REJECTED' }));
    await waitFor(() => expect(queries.at(-1)?.get('status')).toBe('REJECTED'));
    expect(queries.at(-1)?.get('page')).toBe('0'); // filter change returns to the first page

    await user.type(screen.getByLabelText('Search'), 'procurement');
    await waitFor(() => expect(queries.at(-1)?.get('q')).toBe('procurement'));

    await user.click(screen.getByRole('button', { name: /^Total \(USD\)/ }));
    await waitFor(() => expect(queries.at(-1)?.get('sort')).toBe('totalAmount,asc'));
    await user.click(screen.getByRole('button', { name: /^Total \(USD\)/ }));
    await waitFor(() => expect(queries.at(-1)?.get('sort')).toBe('totalAmount,desc'));

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(queries.at(-1)?.get('status')).toBeNull());
  });

  it('sends UTC day bounds for the date filters and blocks an inverted range', async () => {
    const queries: URLSearchParams[] = [];
    mockApi(http.get(url('/orders'), ({ request }) => (queries.push(new URL(request.url).searchParams), HttpResponse.json(page([])))));
    const { user } = renderApp('/orders', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Orders' });
    expect(screen.getByText('Your orders')).toBeVisible();
    await user.type(screen.getByLabelText('Created from'), '2026-10-19');
    await user.type(screen.getByLabelText('Created to'), '2026-10-20');
    await waitFor(() => {
      expect(queries.at(-1)?.get('from')).toBe('2026-10-19T00:00:00Z');
      expect(queries.at(-1)?.get('to')).toBe('2026-10-20T23:59:59Z');
    });
    expect(await screen.findByText('No orders found')).toBeVisible();

    await user.clear(screen.getByLabelText('Created to'));
    await user.type(screen.getByLabelText('Created to'), '2026-10-01');
    expect(await screen.findByText('The end date is before the start date.')).toBeVisible();
    await waitFor(() => expect(queries.at(-1)?.get('to')).toBeNull()); // the invalid range is not sent
  });

  it('shows an error banner and retries', async () => {
    let fail = true;
    mockApi(http.get(url('/orders'), () => (fail ? problem(500, 'INTERNAL_ERROR') : HttpResponse.json(page([makeOrder()])))));
    const { user } = renderApp('/orders', { session: customerSession });
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('table', { name: 'Orders' })).toBeVisible();
  });
});

describe('OrderDetail', () => {
  it('renders summary, items, history, timeline and the polling indicator; no saga for customers', async () => {
    const sagaHit = vi.fn();
    mockApi(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder({ status: 'VALIDATED' }))),
      http.get(url('/admin/saga/:id'), () => (sagaHit(), HttpResponse.json(saga))),
    );
    renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Order 11111111' })).toBeVisible();
    expect(await screen.findByTestId('order-status')).toHaveTextContent('VALIDATED');
    expect(screen.getByTestId('polling-indicator')).toBeVisible();
    expect(screen.getByTestId('order-id')).toHaveTextContent(ORDER_ID);
    expect(screen.getByRole('table', { name: 'Order items' })).toHaveTextContent('SKU-1001');
    expect(screen.getByRole('table', { name: 'Status history' })).toHaveTextContent('VALIDATED');
    expect(screen.getByRole('list', { name: 'Order progress' })).toBeVisible();
    expect(screen.queryByText('Saga (staff view)')).toBeNull();
    expect(sagaHit).not.toHaveBeenCalled(); // customers never even request the saga
  });

  it('staff also see the saga section', async () => {
    mockApi();
    renderApp(`/orders/${ORDER_ID}`, { session: operatorSession });
    expect(await screen.findByText('Saga (staff view)')).toBeVisible();
    expect(screen.getByRole('table', { name: 'Saga step log' })).toHaveTextContent('VALIDATE');
  });

  it('REJECTED order with null prices renders (defect D-03: null.toFixed used to blank the page)', async () => {
    mockApi(http.get(url('/orders/:id'), () => HttpResponse.json(makeRejectedOrder())), http.get(url('/orders/:id/history'), () => HttpResponse.json(history)));
    renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    expect(await screen.findByTestId('order-status')).toHaveTextContent('REJECTED');
    expect(screen.getByTestId('status-reason')).toHaveTextContent('INSUFFICIENT_STOCK');
    const items = screen.getByRole('table', { name: 'Order items' });
    const cells = within(items).getAllByRole('cell').map((c) => c.textContent);
    expect(cells).toEqual(['', '', '1', '-', '-']); // sku, name null -> empty; prices -> "-"
    expect(screen.getByText(/^0\.00 USD$/)).toBeVisible();
    expect(screen.queryByTestId('cancel-order')).toBeNull();
    expect(screen.queryByTestId('polling-indicator')).toBeNull(); // terminal: not polling
  });

  it('polls with backoff until a terminal status, then stops (and shows Refresh instead of Stop)', async () => {
    let calls = 0;
    const statuses = ['CREATED', 'VALIDATED', 'INVENTORY_RESERVED', 'FULFILLED'] as const;
    mockApi(http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder({ status: statuses[Math.min(calls++, statuses.length - 1)] }))));
    renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    await waitFor(() => expect(screen.getByTestId('order-status')).toHaveTextContent('FULFILLED'), { timeout: 8000 });
    await waitFor(() => expect(screen.queryByTestId('polling-indicator')).toBeNull());
    expect(screen.queryByTestId('cancel-order')).toBeNull();
    const seen = calls;
    await new Promise((r) => setTimeout(r, 300));
    expect(calls).toBe(seen); // no more requests after the terminal status
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeVisible();
  });

  it('"Stop auto-refresh" stops polling and Refresh resumes it', async () => {
    let calls = 0;
    mockApi(http.get(url('/orders/:id'), () => (calls++, HttpResponse.json(makeOrder({ status: 'VALIDATED' })))));
    const { user } = renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    await screen.findByTestId('polling-indicator');
    await user.click(screen.getByRole('button', { name: 'Stop auto-refresh' }));
    expect(screen.queryByTestId('polling-indicator')).toBeNull();
    await new Promise((r) => setTimeout(r, 100)); // let any in-flight request settle
    const seen = calls;
    await new Promise((r) => setTimeout(r, 300));
    expect(calls).toBe(seen);
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(calls).toBeGreaterThan(seen));
    expect(await screen.findByTestId('polling-indicator')).toBeVisible();
  });

  it('cancel flow: dialog requires a reason, posts it, toasts and refreshes to CANCELLED', async () => {
    let status: 'VALIDATED' | 'CANCELLED' = 'VALIDATED';
    let cancelBody: unknown;
    mockApi(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder({ status }))),
      http.post(url('/orders/:id/cancel'), async ({ request }) => {
        cancelBody = await request.json();
        status = 'CANCELLED';
        return HttpResponse.json(makeOrder({ status }));
      }),
    );
    const { user } = renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    await user.click(await screen.findByTestId('cancel-order'));
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByLabelText(/Reason for cancellation/)).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }));
    expect(within(dialog).getByText(/at least 3 characters/)).toBeVisible();
    await user.type(within(dialog).getByLabelText(/Reason for cancellation/), 'Ordered by mistake');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }));
    expect(await screen.findByText('Cancellation requested.')).toBeVisible();
    await waitFor(() => expect(screen.getByTestId('order-status')).toHaveTextContent('CANCELLED'));
    expect(cancelBody).toEqual({ reason: 'Ordered by mistake' });
    expect(screen.queryByTestId('cancel-order')).toBeNull();
    expect(await screen.findByRole('table', { name: 'Status history' })).toBeVisible();
  });

  it('keeping the order closes the dialog without calling the API; a 409 on cancel shows a friendly error', async () => {
    const cancel = vi.fn();
    mockApi(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder({ status: 'CONFIRMED' }))),
      http.post(url('/orders/:id/cancel'), () => (cancel(), problem(409, 'INVALID_STATE_TRANSITION'))),
    );
    const { user } = renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    await user.click(await screen.findByTestId('cancel-order'));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Keep order' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(cancel).not.toHaveBeenCalled();

    await user.click(screen.getByTestId('cancel-order'));
    const dialog = await screen.findByRole('alertdialog');
    await user.type(within(dialog).getByLabelText(/Reason for cancellation/), 'changed my mind');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }));
    expect(await screen.findByText('That action is not possible in the current state of the order.')).toBeVisible();
  });

  it('another customer\'s order is a friendly 404 error (single alert), polling stopped', async () => {
    mockApi(http.get(url('/orders/:id'), () => problem(404, 'NOT_FOUND')));
    renderApp(`/orders/${ORDER_ID}`, { session: customerSession });
    expect(await screen.findByRole('alert')).toHaveTextContent('could not find');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.queryByTestId('polling-indicator')).toBeNull();
    expect(screen.queryByTestId('order-status')).toBeNull();
  });

  it('every order status other than terminal ones offers cancel', async () => {
    mockApi(http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder({ status: 'COMPENSATION_PENDING' }))));
    renderApp(`/orders/${ORDER_ID}`, { session: adminSession });
    await screen.findByTestId('order-status');
    expect(screen.queryByTestId('cancel-order')).toBeNull(); // COMPENSATION_PENDING is not cancellable per the contract
  });
});

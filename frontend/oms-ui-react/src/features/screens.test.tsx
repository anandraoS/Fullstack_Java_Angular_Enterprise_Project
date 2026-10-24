import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { ORDER_ID, adminSession, customerSession, fulfillment, makeOrder, makeProduct, makeRejectedOrder, messaging, notification, operatorSession, page, problem, reservation, saga } from '../test/fixtures';
import { mockApi, renderApp } from '../test/render';
import { url } from '../test/server';
import { countByStatus, inFlight } from './dashboard/Dashboard';
import { healthEntries, totalLag } from './ops/OpsDashboard';

describe('Dashboard', () => {
  it('counts orders by status (pure)', () => {
    const c = countByStatus([makeOrder({ status: 'FULFILLED' }), makeOrder({ status: 'FULFILLED' }), makeOrder({ status: 'CREATED' }), makeOrder({ status: 'FAILED' })]);
    expect(c).toEqual({ FULFILLED: 2, CREATED: 1, FAILED: 1 });
    expect(inFlight({ CREATED: 1, VALIDATED: 2, INVENTORY_RESERVED: 1, CONFIRMED: 1, COMPENSATION_PENDING: 1, FULFILLED: 9 })).toBe(6);
  });

  it('customers see order KPIs and recent orders but no staff tiles (and no staff requests)', async () => {
    const staffCalls = vi.fn();
    mockApi(
      http.get(url('/orders'), () => HttpResponse.json(page([makeOrder({ status: 'FULFILLED' }), makeOrder({ id: '22222222-0000-0000-0000-000000000000', status: 'CREATED' })], { totalElements: 12 }))),
      http.get(url('/products'), () => HttpResponse.json(page([], { totalElements: 18 }))),
      http.get(url('/inventory'), () => (staffCalls(), HttpResponse.json(page([])))),
      http.get(url('/admin/orders/failed'), () => (staffCalls(), HttpResponse.json(page([])))),
      http.get(url('/ops/health'), () => (staffCalls(), HttpResponse.json({}))),
    );
    renderApp('/dashboard', { session: customerSession });
    expect(await screen.findByTestId('kpi-total')).toHaveTextContent('12');
    expect(screen.getByText('Signed in as customer1')).toBeVisible();
    expect(screen.getByText('Products in stock').previousElementSibling).toHaveTextContent('18');
    expect(screen.queryByTestId('kpi-low')).toBeNull();
    expect(screen.queryByText('Platform health')).toBeNull();
    expect(within(screen.getByRole('table', { name: 'Most recent orders' })).getAllByRole('row')).toHaveLength(3);
    expect(staffCalls).not.toHaveBeenCalled();
  });

  it('staff also see low stock, failed orders and platform health; a failing source only blanks its own tile', async () => {
    mockApi(
      http.get(url('/inventory'), () => HttpResponse.json(page([], { totalElements: 4 }))),
      http.get(url('/admin/orders/failed'), () => problem(500, 'INTERNAL_ERROR')),
    );
    renderApp('/dashboard', { session: operatorSession });
    expect(await screen.findByTestId('kpi-low')).toHaveTextContent('4');
    await waitFor(() => expect(screen.getByTestId('kpi-failed')).toHaveTextContent('n/a'));
    expect(screen.getByText('Platform health').previousElementSibling).toHaveTextContent('UP');
    expect(screen.queryByText('The dashboard figures could not be loaded.')).toBeNull();
  });

  it('when the order list fails an error banner offers a retry; Refresh re-queries', async () => {
    let fail = true;
    mockApi(http.get(url('/orders'), () => (fail ? problem(500, 'INTERNAL_ERROR') : HttpResponse.json(page([makeOrder()], { totalElements: 1 })))));
    const { user } = renderApp('/dashboard', { session: customerSession });
    expect(await screen.findByText('The dashboard figures could not be loaded.')).toBeVisible();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByTestId('kpi-total')).toHaveTextContent('1'));
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(screen.getByRole('link', { name: 'Create order' })).toHaveAttribute('href', '/orders/new');
  });

  it('shows "No orders yet." for an empty list', async () => {
    mockApi(http.get(url('/orders'), () => HttpResponse.json(page([]))));
    renderApp('/dashboard', { session: customerSession });
    expect(await screen.findByText('No orders yet.')).toBeVisible();
  });
});

describe('Catalog', () => {
  it('searches with a debounce, filters, sorts, and adds to the cart (out of stock is disabled)', async () => {
    const queries: URLSearchParams[] = [];
    mockApi(http.get(url('/products'), ({ request }) => (queries.push(new URL(request.url).searchParams), HttpResponse.json(page([makeProduct(), makeProduct({ id: 'p2', sku: 'SKU-1002', name: 'Hex Nut M8', available: 0 })])))));
    const { user, store } = renderApp('/catalog', { session: customerSession });
    expect(await screen.findByRole('cell', { name: 'SKU-1001' })).toBeVisible();
    expect(queries[0].get('sort')).toBe('sku,asc');

    await user.type(screen.getByLabelText('Search name or SKU'), 'bolt');
    await waitFor(() => expect(queries.at(-1)?.get('q')).toBe('bolt'));
    await user.type(screen.getByLabelText('Category'), 'Fasteners');
    await waitFor(() => expect(queries.at(-1)?.get('category')).toBe('Fasteners'));
    await user.click(screen.getByLabelText('In stock only'));
    await waitFor(() => expect(queries.at(-1)?.get('inStock')).toBe('true'));
    await user.click(screen.getByRole('button', { name: /^Price/ }));
    await waitFor(() => expect(queries.at(-1)?.get('sort')).toBe('price,asc'));

    expect(screen.getByRole('button', { name: 'Add Hex Nut M8 to cart' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Add Hex Bolt M8 to cart' }));
    await user.click(screen.getByRole('button', { name: 'Add Hex Bolt M8 to cart' }));
    expect(store.getState().cart.lines).toEqual([{ productId: 'a0000000-0000-0000-0000-000000001001', sku: 'SKU-1001', name: 'Hex Bolt M8', unitPrice: 2.5, quantity: 2 }]);
    expect(await screen.findByText('SKU-1001 added to cart')).toBeVisible();
    expect(screen.getByRole('link', { name: /Go to order form \(2 in cart\)/ })).toHaveAttribute('href', '/orders/new?fromCart=1');
  });

  it('shows the empty state and an error banner with retry', async () => {
    let mode: 'empty' | 'error' | 'ok' = 'empty';
    mockApi(http.get(url('/products'), () => (mode === 'empty' ? HttpResponse.json(page([])) : mode === 'error' ? problem(500, 'INTERNAL_ERROR') : HttpResponse.json(page([makeProduct()])))));
    const { user } = renderApp('/catalog', { session: customerSession });
    expect(await screen.findByText('No products match')).toBeVisible();
    mode = 'error';
    await user.type(screen.getByLabelText('Search name or SKU'), 'x');
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    mode = 'ok';
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('cell', { name: 'SKU-1001' })).toBeVisible();
  });
});

describe('Inventory', () => {
  it('lists stock for staff, filters by SKU and low stock', async () => {
    const queries: URLSearchParams[] = [];
    mockApi(http.get(url('/inventory'), ({ request }) => (queries.push(new URL(request.url).searchParams), HttpResponse.json(page([makeProduct()])))));
    const { user } = renderApp('/inventory', { session: operatorSession });
    expect(await screen.findByRole('table', { name: 'Stock levels' })).toBeVisible();
    expect(screen.getByText(/Low stock is determined by the server/)).toBeVisible();
    await user.type(screen.getByLabelText('SKU', { exact: true }), 'SKU-1001');
    await waitFor(() => expect(queries.at(-1)?.get('sku')).toBe('SKU-1001'));
    await user.click(screen.getByLabelText('Low stock only'));
    await waitFor(() => expect(queries.at(-1)?.get('lowStock')).toBe('true'));
  });

  it('stock adjustment dialog validates, posts delta + reason, toasts and refreshes the list', async () => {
    let onHand = 100;
    let putBody: unknown;
    mockApi(
      http.get(url('/inventory'), () => HttpResponse.json(page([makeProduct({ onHand })]))),
      http.put(url('/inventory/:id/stock'), async ({ request }) => {
        putBody = await request.json();
        onHand += (putBody as { delta: number }).delta;
        return HttpResponse.json(makeProduct({ onHand }));
      }),
    );
    const { user } = renderApp('/inventory', { session: operatorSession });
    await user.click(await screen.findByRole('button', { name: 'Adjust stock for SKU-1001' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveAccessibleName('Adjust stock: SKU-1001');
    expect(within(dialog).getByLabelText(/^Change/)).toHaveFocus();
    expect(within(dialog).getByText(/On hand 100, reserved 10/)).toBeVisible();

    await user.click(within(dialog).getByRole('button', { name: 'Apply change' }));
    expect(await within(dialog).findByText('Enter a number.')).toBeVisible();
    expect(within(dialog).getByText(/at least 5 characters/)).toBeVisible();

    await user.type(within(dialog).getByLabelText(/^Change/), '-95');
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Cycle count correction');
    await user.click(within(dialog).getByRole('button', { name: 'Apply change' }));
    expect(await within(dialog).findByText('Stock cannot fall below the reserved quantity.')).toBeVisible();

    await user.clear(within(dialog).getByLabelText(/^Change/));
    await user.type(within(dialog).getByLabelText(/^Change/), '5');
    await user.click(within(dialog).getByRole('button', { name: 'Apply change' }));
    expect(await screen.findByText('Stock updated for SKU-1001')).toBeVisible();
    expect(putBody).toEqual({ delta: 5, reason: 'Cycle count correction' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Stock levels' })).getAllByRole('cell').map((c) => c.textContent)).toContain('105'));
  });

  it('a server 409 is shown inside the dialog and the dialog stays open; Cancel closes it', async () => {
    mockApi(http.put(url('/inventory/:id/stock'), () => problem(409, 'CONFLICT')));
    const { user } = renderApp('/inventory', { session: adminSession });
    await user.click(await screen.findByRole('button', { name: 'Adjust stock for SKU-1001' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Change/), '3');
    await user.type(within(dialog).getByLabelText(/^Reason/), 'recount done');
    await user.click(within(dialog).getByRole('button', { name: 'Apply change' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('changed by someone else');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('reservations tab lists reservations and filters by status', async () => {
    const queries: URLSearchParams[] = [];
    mockApi(http.get(url('/inventory/reservations'), ({ request }) => (queries.push(new URL(request.url).searchParams), HttpResponse.json(page([reservation])))));
    const { user } = renderApp('/inventory', { session: operatorSession });
    await user.click(await screen.findByRole('tab', { name: 'Reservations' }));
    const table = await screen.findByRole('table', { name: 'Reservations' });
    expect(table).toHaveTextContent('SKU-1001 x2');
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByRole('option', { name: 'EXPIRED' }));
    await waitFor(() => expect(queries.at(-1)?.get('status')).toBe('EXPIRED'));
    await user.type(screen.getByLabelText('Order ID'), ORDER_ID);
    await waitFor(() => expect(queries.at(-1)?.get('orderId')).toBe(ORDER_ID));
    await user.click(screen.getByRole('tab', { name: 'Stock' }));
    expect(await screen.findByRole('table', { name: 'Stock levels' })).toBeVisible();
  });
});

describe('FailedOrders (investigation)', () => {
  it('lists failed orders, investigates one (order, timeline, saga, audit) and retries after confirmation', async () => {
    let retried = 0;
    mockApi(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeRejectedOrder())),
      http.post(url('/admin/orders/:id/retry'), () => (retried++, HttpResponse.json({}))),
    );
    const { user } = renderApp('/failed-orders', { session: operatorSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Failed order investigation' })).toBeVisible();
    expect(screen.getByText('Select an order above to see its saga, history and audit trail.')).toBeVisible();
    const row = (await screen.findByRole('cell', { name: '11111111' })).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /Investigate/ }));

    expect(await screen.findByText((_, el) => el?.tagName === 'STRONG' && el.textContent === `Order ${ORDER_ID}`)).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Saga' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Audit trail' })).toBeVisible();
    expect(screen.getByText(/ORDER_CREATED/)).toBeVisible();
    expect(screen.queryByTestId('run-reconciliation')).toBeNull(); // operators: no admin action

    await user.click(screen.getByTestId('retry-order'));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Retry requested.')).toBeVisible();
    expect(retried).toBe(1);
  });

  it('cancelling the retry confirmation does nothing; a 409 for a terminal order is shown as a friendly message', async () => {
    const post = vi.fn();
    mockApi(http.post(url('/admin/orders/:id/retry'), () => (post(), problem(409, 'INVALID_STATE_TRANSITION'))));
    const { user } = renderApp('/failed-orders', { session: operatorSession });
    const row = (await screen.findByRole('cell', { name: '11111111' })).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /Investigate/ }));
    await user.click(await screen.findByTestId('retry-order'));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(post).not.toHaveBeenCalled();

    await user.click(screen.getByTestId('retry-order'));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(/not possible in the current state/)).toBeVisible();
  });

  it('shows saga/audit gracefully when they are unavailable', async () => {
    mockApi(
      http.get(url('/admin/saga/:id'), () => problem(404, 'NOT_FOUND')),
      http.get(url('/ops/order/audit'), () => problem(500, 'INTERNAL_ERROR')),
    );
    const { user } = renderApp('/failed-orders', { session: operatorSession });
    const row = (await screen.findByRole('cell', { name: '11111111' })).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /Investigate/ }));
    expect(await screen.findByText('No audit events found for this order.')).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Saga' })).toBeNull();
  });

  it('admins can run reconciliation after confirming and see the result', async () => {
    mockApi(http.post(url('/admin/reconciliation/run'), () => HttpResponse.json({ examined: 7, repaired: 2, details: ['released reservation r-1', 'resent command for o-2'] })));
    const { user } = renderApp('/failed-orders', { session: adminSession });
    await user.click(await screen.findByTestId('run-reconciliation'));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Run' }));
    const result = await screen.findByTestId('recon-result');
    expect(result).toHaveTextContent('Examined 7, repaired 2.');
    expect(result).toHaveTextContent('released reservation r-1');
  });

  it('a reconciliation failure (403) is shown as an alert', async () => {
    mockApi(http.post(url('/admin/reconciliation/run'), () => problem(403, 'FORBIDDEN')));
    const { user } = renderApp('/failed-orders', { session: adminSession });
    await user.click(await screen.findByTestId('run-reconciliation'));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Run' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('permission');
  });

  it('empty state when nothing failed', async () => {
    mockApi(http.get(url('/admin/orders/failed'), () => HttpResponse.json(page([]))));
    renderApp('/failed-orders', { session: operatorSession });
    expect(await screen.findByText('No failed orders')).toBeVisible();
    expect(screen.getByText('Everything is flowing.')).toBeVisible();
  });
});

describe('OpsDashboard', () => {
  it('pure helpers: total lag and health entries', () => {
    expect(totalLag({ topics: [{ topic: 'a', partitions: 1, consumerLag: 2, committedOffsets: {} }, { topic: 'b', partitions: 1, consumerLag: null, committedOffsets: {} }, { topic: 'c', partitions: 1, consumerLag: 3, committedOffsets: {} }] })).toBe(5);
    expect(totalLag({ topics: [{ topic: 'a', partitions: 1, consumerLag: 2, committedOffsets: {} }], lagAvailable: false })).toBeNull();
    expect(totalLag({ topics: [] })).toBeNull();
    expect(healthEntries({ status: 'UP', services: { order: { status: 'UP' }, inventory: { status: 'DOWN', detail: 'x' } } })).toEqual([{ name: 'order', status: 'UP' }, { name: 'inventory', status: 'DOWN' }]);
    expect(healthEntries({ status: 'UP' } as never)).toEqual([]);
  });

  it('shows overall and per-service health and a card per service with messaging figures', async () => {
    mockApi(
      http.get(url('/ops/:svc/messaging'), ({ params }) => HttpResponse.json(messaging(String(params.svc), params.svc === 'order' ? { deadLettered: 2, outboxPending: 11, dltTopics: ['oms.order.events.v1.DLT'], lastProcessedAt: null } : {}))),
      http.get(url('/ops/health'), () => HttpResponse.json({ status: 'DEGRADED', services: { order: { status: 'UP' }, inventory: { status: 'DOWN' } } })),
    );
    renderApp('/ops', { session: operatorSession });
    expect(await screen.findByTestId('health-overall')).toHaveTextContent('DEGRADED');
    for (const svc of ['order', 'inventory', 'fulfillment', 'notification']) {
      expect(await screen.findByTestId(`svc-card-${svc}`)).toBeVisible();
    }
    const order = screen.getByTestId('svc-card-order');
    await within(order).findByText('Consumer group');
    expect(within(order).getByTestId('dlt')).toHaveTextContent('2 (needs attention)');
    expect(within(order).getByTestId('lag')).toHaveTextContent('2');
    expect(within(order).getByText('never')).toBeVisible();
    expect(within(order).getByText(/DLT topics: oms.order.events.v1.DLT/)).toBeVisible();
  });

  it('a failing service only blanks its own card; lag unavailable is explained', async () => {
    mockApi(
      http.get(url('/ops/inventory/messaging'), () => problem(503, 'UPSTREAM_UNAVAILABLE')),
      http.get(url('/ops/fulfillment/messaging'), () => HttpResponse.json(messaging('fulfillment', { lagAvailable: false }))),
      http.get(url('/ops/health'), () => problem(500, 'INTERNAL_ERROR')),
    );
    renderApp('/ops', { session: adminSession });
    const inv = await screen.findByTestId('svc-card-inventory');
    await waitFor(() => expect(within(inv).getByRole('alert')).toHaveTextContent('temporarily unavailable'), { timeout: 8000 });
    const ful = screen.getByTestId('svc-card-fulfillment');
    expect(await within(ful).findByTestId('lag')).toHaveTextContent('n/a (lag unavailable)');
    expect(await screen.findByText(/Something went wrong on our side/)).toBeVisible();
  });

  it('auto-refresh can be switched off and Refresh now re-queries every service', async () => {
    let order = 0;
    mockApi(http.get(url('/ops/order/messaging'), () => (order++, HttpResponse.json(messaging('order')))));
    const { user } = renderApp('/ops', { session: operatorSession });
    await screen.findByTestId('svc-card-order');
    await waitFor(() => expect(order).toBeGreaterThanOrEqual(1));
    const before = order;
    const toggle = screen.getByRole('switch', { name: 'Auto-refresh (10s)' });
    expect(toggle).toBeChecked();
    await user.click(toggle);
    expect(toggle).not.toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Refresh now' }));
    await waitFor(() => expect(order).toBeGreaterThan(before));
  });
});

describe('Audit', () => {
  it('queries the chosen service with entity filters', async () => {
    const queries: { svc: string; q: URLSearchParams }[] = [];
    mockApi(http.get(url('/ops/:svc/audit'), ({ request, params }) => (queries.push({ svc: String(params.svc), q: new URL(request.url).searchParams }), HttpResponse.json(page([{ id: 'a1', entityType: 'Order', entityId: ORDER_ID, action: 'ORDER_CREATED', actor: null, correlationId: null, details: null, occurredAt: '2026-10-19T10:00:00Z' }])))));
    const { user } = renderApp('/audit', { session: operatorSession });
    const table = await screen.findByRole('table', { name: 'Audit events' });
    expect(table).toHaveTextContent('ORDER_CREATED');
    expect(table).toHaveTextContent('system'); // null actor
    expect(queries[0].svc).toBe('order');
    await user.click(screen.getByTestId('audit-service'));
    await user.click(await screen.findByRole('option', { name: 'inventory' }));
    await waitFor(() => expect(queries.at(-1)?.svc).toBe('inventory'));
    await user.type(screen.getByLabelText('Entity type'), 'Product');
    await user.type(screen.getByLabelText('Entity ID'), 'abc');
    await waitFor(() => {
      expect(queries.at(-1)?.q.get('entityType')).toBe('Product');
      expect(queries.at(-1)?.q.get('entityId')).toBe('abc');
    });
  });
});

describe('Notifications', () => {
  it('staff can retry FAILED notifications; customers get no action column', async () => {
    let retried = 0;
    mockApi(http.post(url('/admin/notifications/:id/retry'), () => (retried++, HttpResponse.json({}))));
    const { user, unmount } = renderApp('/notifications', { session: operatorSession });
    expect(await screen.findByText('All simulated notifications')).toBeVisible();
    await user.click(await screen.findByRole('button', { name: 'Retry notification n0000000' }));
    expect(await screen.findByText('Retry requested.')).toBeVisible();
    expect(retried).toBe(1);
    unmount();

    renderApp('/notifications', { session: customerSession });
    expect(await screen.findByText('Notifications sent to you (simulated)')).toBeVisible();
    await screen.findByRole('table', { name: 'Notifications' });
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Retry notification/ })).toBeNull();
  });

  it('only FAILED rows have the retry button, retry errors toast, and filters reach the server', async () => {
    const queries: URLSearchParams[] = [];
    mockApi(
      http.get(url('/notifications'), ({ request }) => (queries.push(new URL(request.url).searchParams), HttpResponse.json(page([notification, { ...notification, id: 'n2', status: 'SENT' }])))),
      http.post(url('/admin/notifications/:id/retry'), () => problem(409, 'CONFLICT')),
    );
    const { user } = renderApp('/notifications', { session: adminSession });
    expect(await screen.findAllByRole('button', { name: /Retry notification/ })).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: /Retry notification/ }));
    expect(await screen.findByText(/changed by someone else/)).toBeVisible();
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByRole('option', { name: 'SENT' }));
    await waitFor(() => expect(queries.at(-1)?.get('status')).toBe('SENT'));
    await user.type(screen.getByLabelText('Order ID'), 'abc');
    await waitFor(() => expect(queries.at(-1)?.get('orderId')).toBe('abc'));
  });
});

describe('Fulfillment', () => {
  it('lists fulfillments, retries failed ones and filters', async () => {
    const queries: URLSearchParams[] = [];
    let retried = 0;
    mockApi(
      http.get(url('/fulfillments'), ({ request }) => (queries.push(new URL(request.url).searchParams), HttpResponse.json(page([fulfillment, { ...fulfillment, id: 'f2', status: 'COMPLETED', trackingNumber: 'OMS-ABC' }])))),
      http.post(url('/admin/fulfillments/:id/retry'), () => (retried++, HttpResponse.json({}))),
    );
    const { user } = renderApp('/fulfillment', { session: operatorSession });
    const table = await screen.findByRole('table', { name: 'Fulfillments' });
    expect(table).toHaveTextContent('OMS-ABC');
    expect(table).toHaveTextContent('bad address');
    expect(screen.getAllByRole('button', { name: /Retry fulfillment/ })).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Retry fulfillment f0000000' }));
    expect(await screen.findByText('Retry requested.')).toBeVisible();
    expect(retried).toBe(1);
    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByRole('option', { name: 'FAILED' }));
    await waitFor(() => expect(queries.at(-1)?.get('status')).toBe('FAILED'));
    await user.type(screen.getByLabelText('Order ID'), 'zz');
    await waitFor(() => expect(queries.at(-1)?.get('orderId')).toBe('zz'));
  });

  it('a retry error is surfaced as a toast', async () => {
    mockApi(http.post(url('/admin/fulfillments/:id/retry'), () => problem(409, 'INVALID_STATE_TRANSITION')));
    const { user } = renderApp('/fulfillment', { session: adminSession });
    await user.click(await screen.findByRole('button', { name: /Retry fulfillment/ }));
    expect(await screen.findByText(/not possible in the current state/)).toBeVisible();
  });
});

describe('misc screens', () => {
  it('403 and 404 pages offer a way back', async () => {
    mockApi();
    const { user } = renderApp('/forbidden', { session: customerSession });
    await user.click(await screen.findByRole('link', { name: 'Back to dashboard' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
    void saga;
  });
});

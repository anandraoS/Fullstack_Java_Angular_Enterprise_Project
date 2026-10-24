import { expect, test } from '@playwright/test';
import { API_URL, CUSTOMER_1, PASSWORD, apiToken, fillOrderForm, login, loginOk, logout, placeOrder, statusChip, waitForStatus } from './helpers';

/**
 * Main order lifecycle against either backend (see playwright.config.ts). Serial: the failed order created in step 6
 * is investigated by an operator in step 7.
 */
test.describe.configure({ mode: 'serial' });

let failedOrderId = '';

test.describe('authentication and navigation', () => {
  test('wrong password shows a generic error and stays on the login page', async ({ page }) => {
    await login(page, 'customer1', 'definitely-wrong-password');
    await expect(page.getByRole('alert')).toContainText('Sign-in failed');
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByLabel('Password')).toHaveValue('');
  });

  test('unauthenticated access redirects to login and returns after sign-in', async ({ page }) => {
    await page.goto('/orders');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Forders/);
    await page.getByLabel('Username').fill('customer1');
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('heading', { name: 'Orders', level: 1 })).toBeVisible();
  });

  test('customers get customer navigation and are kept out of staff screens (UX guard)', async ({ page }) => {
    await loginOk(page, 'customer1');
    const nav = page.getByRole('navigation', { name: 'Main navigation' });
    await expect(nav.getByRole('link', { name: 'Catalog' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Inventory' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Operations' })).toHaveCount(0);
    await page.goto('/inventory');
    await expect(page.getByRole('heading', { name: 'Not allowed', level: 1 })).toBeVisible();
    await logout(page, 'customer1');
  });

  test('operators see staff navigation', async ({ page }) => {
    await loginOk(page, 'operator1');
    const nav = page.getByRole('navigation', { name: 'Main navigation' });
    for (const name of ['Inventory', 'Fulfillment', 'Failed orders', 'Operations', 'Audit history']) {
      await expect(nav.getByRole('link', { name })).toBeVisible();
    }
  });
});

test.describe('order lifecycle', () => {
  test('1. catalog search and add to cart feeds the order form', async ({ page }) => {
    await loginOk(page, 'customer1');
    await page.goto('/catalog');
    await page.getByLabel('Search name or SKU').fill('SKU-1001');
    await expect(page.getByRole('cell', { name: 'SKU-1001', exact: true })).toBeVisible();
    await page.getByRole('button', { name: /^Add .* to cart$/ }).first().click();
    await page.getByRole('link', { name: /Go to order form/ }).click();
    await expect(page).toHaveURL(/\/orders\/new\?fromCart=1/);
    await expect(page.getByTestId('product-0')).toContainText('SKU-1001');
  });

  test('2. create order and watch it reach FULFILLED', async ({ page }) => {
    await loginOk(page, 'customer1');
    await placeOrder(page, { sku: 'SKU-1001', quantity: 2 });
    await expect(page.getByRole('heading', { name: /^Order / , level: 1 })).toBeVisible();
    await expect(page.getByTestId('polling-indicator')).toBeVisible();
    await waitForStatus(page, 'FULFILLED');
    await expect(page.getByTestId('polling-indicator')).toHaveCount(0); // polling stopped at terminal status
    await expect(page.getByTestId('cancel-order')).toHaveCount(0);
    await expect(page.getByRole('table', { name: 'Status history' })).toContainText('FULFILLED');
    // customers must not see the staff saga section
    await expect(page.getByText('Saga (staff view)')).toHaveCount(0);
  });

  test('3. cancel path: confirm dialog with reason ends in CANCELLED', async ({ page }) => {
    await loginOk(page, 'customer1');
    await placeOrder(page, { sku: 'SKU-1002', quantity: 1 });
    await page.getByTestId('cancel-order').click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('Reason for cancellation')).toBeFocused(); // focus moved into the dialog
    await dialog.getByRole('button', { name: 'Cancel order' }).click(); // reason required: stays open
    await expect(dialog.getByText(/at least 3 characters/)).toBeVisible();
    await dialog.getByLabel('Reason for cancellation').fill('Ordered by mistake');
    await dialog.getByRole('button', { name: 'Cancel order' }).click();
    await expect(dialog).toBeHidden();
    await waitForStatus(page, 'CANCELLED');
    await expect(page.getByRole('table', { name: 'Status history' })).toContainText('CANCELLED');
  });

  test('4. insufficient stock (SKU-1020) is rejected with a reason', async ({ page }) => {
    await loginOk(page, 'customer1');
    await placeOrder(page, { sku: 'SKU-1020', quantity: 1 });
    await waitForStatus(page, 'REJECTED');
    await expect(page.getByTestId('status-reason')).toContainText(/stock/i);
    await expect(page.getByTestId('cancel-order')).toHaveCount(0);
  });

  test('5. duplicate submit is idempotent: a retry after an unknown outcome reuses the key and creates one order', async ({ page, request }) => {
    await loginOk(page, 'customer1');
    const token = await apiToken(request, 'customer1');
    const count = async () => {
      const r = await request.get(`${API_URL}/api/v1/orders?size=1`, { headers: { Authorization: `Bearer ${token}` } });
      return (await r.json()).totalElements as number;
    };
    const before = await count();

    // First POST reaches the server but the response is lost (simulated network failure after processing).
    const keys: string[] = [];
    let first = true;
    await page.route('**/api/v1/orders', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      keys.push(route.request().headers()['idempotency-key']);
      if (first) {
        first = false;
        await route.fetch(); // server processes it
        return route.abort('connectionreset'); // client never sees the answer
      }
      return route.continue();
    });

    await fillOrderForm(page, { sku: 'SKU-1003', quantity: 1, line1: 'Idempotency Street' });
    await page.getByRole('button', { name: 'Place order' }).click();
    await expect(page.getByText(/safe to submit again/)).toBeVisible();
    await page.getByRole('button', { name: 'Place order' }).click();
    await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/);

    expect(keys.length).toBe(2);
    expect(keys[0]).toBe(keys[1]); // SAME key on retry
    expect(keys[0]).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(await count()).toBe(before + 1); // exactly one order was created
    await expect(page.getByText(/already accepted|Order accepted/).first()).toBeVisible();
  });

  test('5b. API contract: same key + same body replays; same key + different body is 422; missing key is 400', async ({ request }) => {
    const token = await apiToken(request, 'customer1');
    const headers = { Authorization: `Bearer ${token}` };
    const products = await (await request.get(`${API_URL}/api/v1/products?size=100`, { headers })).json();
    const prod = products.content.find((p: { sku: string }) => p.sku === 'SKU-1001');
    const body = { customerId: CUSTOMER_1, items: [{ productId: prod.id, quantity: 1 }], shippingAddress: { line1: '9 Replay Rd', city: 'Springfield', postalCode: '12345', country: 'US' } };
    const key = `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const a = await request.post(`${API_URL}/api/v1/orders`, { headers: { ...headers, 'Idempotency-Key': key }, data: body });
    expect(a.status()).toBe(202);
    const b = await request.post(`${API_URL}/api/v1/orders`, { headers: { ...headers, 'Idempotency-Key': key }, data: body });
    expect(b.status()).toBe(202);
    expect(b.headers()['idempotent-replay']).toBe('true');
    expect((await b.json()).id).toBe((await a.json()).id);
    const c = await request.post(`${API_URL}/api/v1/orders`, { headers: { ...headers, 'Idempotency-Key': key }, data: { ...body, items: [{ productId: prod.id, quantity: 2 }] } });
    expect(c.status()).toBe(422);
    expect((await c.json()).code).toBe('IDEMPOTENCY_KEY_REUSED');
    const d = await request.post(`${API_URL}/api/v1/orders`, { headers, data: body });
    expect(d.status()).toBe(400);
    expect((await d.json()).code).toBe('IDEMPOTENCY_KEY_REQUIRED');
  });

  test('6. failed fulfillment (city FAILTOWN) compensates and ends FAILED', async ({ page }) => {
    await loginOk(page, 'customer1');
    failedOrderId = await placeOrder(page, { sku: 'SKU-1004', quantity: 1, city: 'FAILTOWN' });
    await waitForStatus(page, 'FAILED', 80_000);
    await expect(page.getByTestId('status-reason')).toContainText(/fulfil/i);
    const timeline = page.getByRole('list', { name: 'Order progress' });
    await expect(timeline).toContainText('COMPENSATION PENDING');
    await expect(timeline.locator('li.state-failed')).toContainText('FAILED');
    await expect(timeline.locator('li.state-skipped')).toContainText('FULFILLED');
  });

  test('7. operator investigates the failed order and retries', async ({ page }) => {
    test.skip(!failedOrderId, 'requires the failed order from step 6');
    await loginOk(page, 'operator1');
    await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Failed orders' }).click();
    await expect(page.getByRole('heading', { name: 'Failed order investigation', level: 1 })).toBeVisible();
    const row = page.getByRole('row').filter({ hasText: failedOrderId.slice(0, 8) });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: /Investigate/ }).click();
    await expect(page.getByRole('heading', { name: 'Investigation', exact: true })).toBeVisible();
    await expect(page.getByText(`Order ${failedOrderId}`)).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Saga', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Audit trail', exact: true })).toBeVisible();
    await page.getByTestId('retry-order').click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByRole('button', { name: 'Retry' }).click();
    // Mock backend accepts the retry; the real order-service answers 409 INVALID_STATE_TRANSITION for a TERMINAL (FAILED) order
    // (contract section 13: terminal means terminal), which the UI renders as a friendly message. Both are valid outcomes here.
    await expect(page.getByText(/Retry requested\.|not possible in the current state/).first()).toBeVisible();
    // operators do not get the admin-only reconciliation action
    await expect(page.getByTestId('run-reconciliation')).toHaveCount(0);
    // the order detail shows the saga for staff
    await page.goto(`/orders/${failedOrderId}`);
    await expect(page.getByText('Saga (staff view)')).toBeVisible();
    await expect(statusChip(page)).toContainText('FAILED');
  });
});

test.describe('operations and administration', () => {
  test('operator adjusts stock with a reason and sees low-stock filter results', async ({ page }) => {
    await loginOk(page, 'operator1');
    await page.goto('/inventory');
    await page.getByLabel('SKU', { exact: true }).fill('SKU-1001');
    const row = page.getByRole('row').filter({ hasText: 'SKU-1001' });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: 'Adjust stock for SKU-1001' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Apply change' }).click(); // empty form is blocked
    await expect(dialog.getByText('Enter a number.')).toBeVisible();
    await dialog.getByLabel(/^Change/).fill('5');
    await dialog.getByLabel('Reason').fill('Cycle count correction');
    await dialog.getByRole('button', { name: 'Apply change' }).click();
    await expect(page.getByText('Stock updated for SKU-1001')).toBeVisible();
    await page.getByLabel('SKU', { exact: true }).fill('');
    await page.getByLabel('Low stock only').check();
    await expect(page.getByRole('cell', { name: 'SKU-1020', exact: true })).toBeVisible();
  });

  test('operations dashboard shows health and messaging for every service', async ({ page }) => {
    await loginOk(page, 'operator1');
    await page.goto('/ops');
    await expect(page.getByTestId('health-overall')).toContainText('UP');
    for (const svc of ['order', 'inventory', 'fulfillment', 'notification']) {
      await expect(page.getByTestId(`svc-card-${svc}`)).toBeVisible();
    }
    await expect(page.getByTestId('svc-card-order').getByText('Consumer group')).toBeVisible();
  });

  test('audit history for the order service lists events', async ({ page }) => {
    await loginOk(page, 'operator1');
    await page.goto('/audit');
    await expect(page.getByRole('table', { name: 'Audit events' })).toContainText(/ORDER_/);
  });

  test('fulfillment and notifications views list the activity', async ({ page }) => {
    await loginOk(page, 'operator1');
    await page.goto('/fulfillment');
    await expect(page.getByRole('table', { name: 'Fulfillments' })).toContainText(/COMPLETED|FAILED/);
    await page.goto('/notifications');
    await expect(page.getByRole('table', { name: 'Notifications' })).toContainText(/Order (\S+ )?(confirmed|shipped|fulfilled|failed|cancelled|rejected|created)/i) // mock: "Order confirmed"; real: "Order <id8> failed";
  });

  test('admin runs reconciliation', async ({ page }) => {
    await loginOk(page, 'admin1');
    await page.goto('/failed-orders');
    await page.getByTestId('run-reconciliation').click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Run' }).click();
    await expect(page.getByTestId('recon-result')).toContainText('Examined');
  });

  test('customer sees only own orders and a 404 for another customers order', async ({ page, request }) => {
    const token1 = await apiToken(request, 'customer1');
    const mine = await (await request.get(`${API_URL}/api/v1/orders?size=1`, { headers: { Authorization: `Bearer ${token1}` } })).json();
    await loginOk(page, 'customer2');
    await page.goto(`/orders/${mine.content[0].id}`);
    await expect(page.getByRole('alert')).toContainText(/could not find/i);
  });
});

import { act, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { safeReturnUrl } from './components/guards';
import { NAV_ITEMS, visibleNav } from './layout/AppShell';
import { adminSession, customerSession, makeOrder, operatorSession, problem } from './test/fixtures';
import { mockApi, renderApp } from './test/render';
import { server, url } from './test/server';
import { cartAdd } from './store/cartSlice';

const tokenBody = (roles: string[], username: string) => ({ accessToken: 'tok-new', tokenType: 'Bearer', expiresIn: 3600, username, roles, customerId: null });

describe('role-aware navigation (UX only)', () => {
  it('visibleNav filters by role', () => {
    expect(visibleNav(NAV_ITEMS, ['CUSTOMER']).map((i) => i.label)).toEqual(['Dashboard', 'Catalog', 'Orders', 'New order', 'Notifications']);
    const staff = visibleNav(NAV_ITEMS, ['OPERATOR']).map((i) => i.label);
    for (const l of ['Inventory', 'Fulfillment', 'Failed orders', 'Operations', 'Audit history']) expect(staff).toContain(l);
    expect(visibleNav(NAV_ITEMS, ['ADMIN'])).toHaveLength(NAV_ITEMS.length);
  });

  it('customers see customer links only, in a labelled navigation landmark', async () => {
    mockApi();
    renderApp('/dashboard', { session: customerSession });
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getByRole('link', { name: 'Catalog' })).toBeVisible();
    expect(within(nav).queryByRole('link', { name: 'Inventory' })).toBeNull();
    expect(within(nav).queryByRole('link', { name: 'Operations' })).toBeNull();
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
  });

  it('operators see staff links', async () => {
    mockApi();
    renderApp('/dashboard', { session: operatorSession });
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    for (const name of ['Inventory', 'Fulfillment', 'Failed orders', 'Operations', 'Audit history']) {
      expect(within(nav).getByRole('link', { name })).toBeVisible();
    }
  });

  it('"Orders" is not highlighted on /orders/new (exact match only for the list)', async () => {
    mockApi();
    renderApp('/orders/new', { session: customerSession });
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getByRole('link', { name: 'New order' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Orders' })).not.toHaveAttribute('aria-current');
  });
});

describe('route guards', () => {
  it('unauthenticated access redirects to /login keeping an encoded returnUrl, and shows the login heading', async () => {
    mockApi();
    renderApp('/orders?x=1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible();
    expect(screen.queryByRole('navigation', { name: 'Main navigation' })).toBeNull();
  });

  it('a customer on a staff route gets the 403 page (client-side guard only)', async () => {
    mockApi();
    renderApp('/inventory', { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Not allowed' })).toBeVisible();
  });

  it.each(['/inventory', '/fulfillment', '/failed-orders', '/ops', '/audit'])('staff-only route %s is blocked for customers', async (path) => {
    mockApi();
    renderApp(path, { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Not allowed' })).toBeVisible();
  });

  it('staff can open a staff route', async () => {
    mockApi();
    renderApp('/audit', { session: operatorSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Audit history' })).toBeVisible();
  });

  it('unknown URLs show the 404 page and "/" goes to the dashboard', async () => {
    mockApi();
    const { unmount } = renderApp('/does/not/exist', { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
    unmount();
    renderApp('/', { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
  });

  it('signed-in users are bounced away from /login (to a safe returnUrl when given)', async () => {
    mockApi();
    renderApp('/login?returnUrl=%2Fcatalog', { session: customerSession });
    expect(await screen.findByRole('heading', { level: 1, name: 'Product catalog' })).toBeVisible();
  });

  it('safeReturnUrl blocks open redirects', () => {
    expect(safeReturnUrl('/orders')).toBe('/orders');
    expect(safeReturnUrl('//evil.example')).toBe('/dashboard');
    expect(safeReturnUrl('https://evil.example')).toBe('/dashboard');
    expect(safeReturnUrl('/a?next=http://x')).toBe('/dashboard');
    expect(safeReturnUrl('/\\evil')).toBe('/dashboard');
    expect(safeReturnUrl('orders')).toBe('/dashboard');
    expect(safeReturnUrl(null)).toBe('/dashboard');
  });
});

describe('login', () => {
  it('has no prefilled credentials and a password field of type password', async () => {
    renderApp('/login');
    expect(await screen.findByLabelText(/Username/)).toHaveValue('');
    const pw = screen.getByLabelText(/Password/);
    expect(pw).toHaveValue('');
    expect(pw).toHaveAttribute('type', 'password');
  });

  it('validates required fields without calling the API', async () => {
    const hit = vi.fn();
    server.use(http.post(url('/auth/token'), () => (hit(), HttpResponse.json({}))));
    const { user } = renderApp('/login');
    await user.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Username is required.')).toBeVisible();
    expect(screen.getByText('Password is required.')).toBeVisible();
    expect(hit).not.toHaveBeenCalled();
  });

  it('signs in, stores the session in memory + sessionStorage-compatible shape, and lands on the dashboard', async () => {
    let body: unknown;
    mockApi(http.post(url('/auth/token'), async ({ request }) => ((body = await request.json()), HttpResponse.json(tokenBody(['CUSTOMER'], 'customer1')))));
    const { user, store } = renderApp('/login');
    await user.type(await screen.findByLabelText(/Username/), '  customer1 ');
    await user.type(screen.getByLabelText(/Password/), 'pw-123456');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
    expect(body).toEqual({ username: 'customer1', password: 'pw-123456' }); // username trimmed
    expect(store.getState().auth.session?.accessToken).toBe('tok-new');
  });

  it('returns to the originally requested page after sign-in', async () => {
    mockApi(http.post(url('/auth/token'), () => HttpResponse.json(tokenBody(['CUSTOMER'], 'customer1'))));
    const { user } = renderApp('/orders');
    await user.type(await screen.findByLabelText(/Username/), 'customer1');
    await user.type(screen.getByLabelText(/Password/), 'pw-123456');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders' })).toBeVisible();
  });

  it('a wrong password shows ONE generic alert, never the server detail, and clears the password', async () => {
    server.use(http.post(url('/auth/token'), () => problem(401, 'UNAUTHENTICATED', { detail: 'user customer1 exists but password mismatch' })));
    const { user } = renderApp('/login');
    await user.type(await screen.findByLabelText(/Username/), 'customer1');
    await user.type(screen.getByLabelText(/Password/), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Sign-in failed. Check your username and password.');
    expect(alert).not.toHaveTextContent('mismatch');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByLabelText(/Password/)).toHaveValue('');
    expect(screen.getByLabelText(/Password/)).toHaveFocus();
  });

  it('other failures (503) keep their own friendly message', async () => {
    server.use(http.post(url('/auth/token'), () => problem(503, 'UPSTREAM_UNAVAILABLE')));
    const { user } = renderApp('/login');
    await user.type(await screen.findByLabelText(/Username/), 'customer1');
    await user.type(screen.getByLabelText(/Password/), 'whatever-pw');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable');
  });

  it('shows the "session expired" notice for ?reason=expired', async () => {
    renderApp('/login?reason=expired');
    expect(await screen.findByRole('status')).toHaveTextContent('Your session has expired');
  });
});

describe('sign out, cart link and expiry', () => {
  it('account menu shows roles and Sign out returns to the login page and clears the session', async () => {
    mockApi();
    const { user, store } = renderApp('/dashboard', { session: adminSession });
    await user.click(await screen.findByRole('button', { name: 'Account menu for admin1' }));
    expect(await screen.findByText('Roles: ADMIN')).toBeVisible();
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible();
    expect(store.getState().auth.session).toBeNull();
  });

  it('the toolbar shows a cart link with the item count once something is in the cart', async () => {
    mockApi();
    const { store } = renderApp('/dashboard', { session: customerSession });
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' });
    expect(screen.queryByRole('link', { name: /Cart:/ })).toBeNull();
    act(() => {
      store.dispatch(cartAdd({ product: { id: 'p1', sku: 'S', name: 'N', price: 2 }, qty: 3 }));
    });
    const link = await screen.findByRole('link', { name: 'Cart: 3 items, go to order form' });
    expect(link).toHaveAttribute('href', '/orders/new?fromCart=1');
  });

  it('a 401 from any API call signs the user out and shows the expired notice', async () => {
    mockApi(http.get(url('/orders'), () => problem(401, 'UNAUTHENTICATED')));
    const { store } = renderApp('/orders', { session: customerSession });
    await waitFor(() => expect(store.getState().auth.session).toBeNull());
    expect(await screen.findByText('Your session has expired. Please sign in again.')).toBeVisible();
  });

  it('logs out automatically when the token lifetime ends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mockApi();
      const { store } = renderApp('/dashboard', { session: { ...customerSession, expiresAt: Date.now() + 5000 } });
      await screen.findByRole('heading', { level: 1, name: 'Dashboard' });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(6000);
      });
      expect(store.getState().auth.session).toBeNull();
      expect(store.getState().auth.logoutReason).toBe('expired');
      expect(await screen.findByText('Your session has expired. Please sign in again.')).toBeVisible();
    } finally {
      vi.useRealTimers();
    }
  });

  it('skip link moves focus to the main region', async () => {
    mockApi();
    const { user } = renderApp('/dashboard', { session: customerSession });
    await user.click(await screen.findByText('Skip to main content'));
    expect(document.getElementById('main')).toHaveFocus();
  });

  it('a page that throws while rendering is contained by the error boundary', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    // an order whose payload breaks rendering (items is not an array)
    mockApi(http.get(url('/orders/:id'), () => HttpResponse.json({ ...makeOrder(), items: null })), http.get(url('/orders/:id/history'), () => HttpResponse.json([])));
    renderApp('/orders/11111111-2222-3333-4444-555555555555', { session: customerSession });
    expect(await screen.findByText('Something went wrong while showing this page.')).toBeVisible();
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeVisible(); // the shell survives
  });
});

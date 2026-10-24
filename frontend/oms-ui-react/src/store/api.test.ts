import { http, HttpResponse } from 'msw';
import { createAppStore } from '.';
import { omsApi, toParams } from './api';
import { loggedOut } from './authSlice';
import { customerSession, operatorSession, makeOrder, ORDER_ID, page, problem } from '../test/fixtures';
import { server, url } from '../test/server';

const signedIn = (session = customerSession) => createAppStore({ preloadedAuth: { session, logoutReason: null }, storage: null });

describe('toParams', () => {
  it('drops undefined, null and empty values and stringifies the rest', () => {
    expect(toParams({ a: 1, b: '', c: undefined, d: null, e: false, f: true, g: 'x' })).toEqual({ a: '1', e: 'false', f: 'true', g: 'x' });
  });
});

describe('request headers', () => {
  it('sends a bearer token and a fresh X-Correlation-Id on API calls', async () => {
    const seen: Headers[] = [];
    server.use(
      http.get(url('/orders'), ({ request }) => {
        seen.push(request.headers);
        return HttpResponse.json(page([]));
      }),
    );
    const store = signedIn();
    await store.dispatch(omsApi.endpoints.listOrders.initiate({ page: 0 }));
    await store.dispatch(omsApi.endpoints.listOrders.initiate({ page: 1 }));
    expect(seen[0].get('authorization')).toBe('Bearer tok-customer');
    expect(seen[0].get('x-correlation-id')).toMatch(/^[0-9a-f-]{36}$/);
    expect(seen[1].get('x-correlation-id')).not.toBe(seen[0].get('x-correlation-id'));
  });

  it('never sends the bearer token to the token endpoint', async () => {
    let auth: string | null = 'unset';
    server.use(
      http.post(url('/auth/token'), ({ request }) => {
        auth = request.headers.get('authorization');
        return HttpResponse.json({ accessToken: 't', tokenType: 'Bearer', expiresIn: 3600, username: 'customer1', roles: ['CUSTOMER'], customerId: null });
      }),
    );
    const store = signedIn();
    await store.dispatch(omsApi.endpoints.login.initiate({ username: 'customer1', password: 'pw' }));
    expect(auth).toBeNull();
  });

  it('sends no Authorization header when signed out', async () => {
    let auth: string | null = 'unset';
    server.use(http.get(url('/orders'), ({ request }) => ((auth = request.headers.get('authorization')), HttpResponse.json(page([])))));
    const store = createAppStore({ storage: null, preloadedAuth: { session: null, logoutReason: null } });
    await store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    expect(auth).toBeNull();
  });
});

describe('error normalization and side effects', () => {
  it('turns a problem+json failure into a serializable ApiError', async () => {
    server.use(http.get(url('/orders'), () => problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'size', message: 'too big' }] })));
    const store = signedIn();
    const res = await store.dispatch(omsApi.endpoints.listOrders.initiate({ size: 1000 }));
    expect(res.error).toMatchObject({ status: 400, code: 'VALIDATION_FAILED', correlationId: 'corr-err', fieldErrors: [{ field: 'size', message: 'too big' }] });
    expect(JSON.parse(JSON.stringify(res.error))).toEqual(res.error); // serializable
  });

  it('retries idempotent GETs on 503 (then succeeds) but gives up after two retries', async () => {
    let calls = 0;
    server.use(
      http.get(url('/orders'), () => {
        calls++;
        return calls < 3 ? new HttpResponse(null, { status: 503 }) : HttpResponse.json(page([makeOrder()]));
      }),
    );
    const store = signedIn();
    const ok = await store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    expect(calls).toBe(3);
    expect(ok.data?.content).toHaveLength(1);

    let down = 0;
    server.use(http.get(url('/products'), () => (down++, problem(503, 'UPSTREAM_UNAVAILABLE'))));
    const bad = await store.dispatch(omsApi.endpoints.listProducts.initiate({}));
    expect(down).toBe(3); // 1 + 2 retries
    expect(bad.error).toMatchObject({ status: 503, code: 'UPSTREAM_UNAVAILABLE' });
  });

  it('does NOT retry non-transient GET failures (404) or any POST (even 503)', async () => {
    let gets = 0;
    server.use(http.get(url('/orders/:id'), () => (gets++, problem(404, 'NOT_FOUND'))));
    server.use(http.get(url('/orders/:id/history'), () => HttpResponse.json([])));
    const store = signedIn();
    await store.dispatch(omsApi.endpoints.getOrderSnapshot.initiate({ id: ORDER_ID, withSaga: false }));
    expect(gets).toBe(1);

    let posts = 0;
    server.use(http.post(url('/orders'), () => (posts++, problem(503, 'UPSTREAM_UNAVAILABLE'))));
    const res = await store.dispatch(
      omsApi.endpoints.createOrder.initiate({ body: { customerId: 'c', items: [], shippingAddress: { line1: 'a', city: 'b', postalCode: '123', country: 'US' } }, key: 'oms-key-12345' }),
    );
    expect(posts).toBe(1);
    expect('error' in res && res.error).toMatchObject({ status: 503 });
  });

  it('network failures become status 0 and are retried for GETs', async () => {
    let calls = 0;
    server.use(http.get(url('/ops/health'), () => (calls++, HttpResponse.error())));
    const store = signedIn(operatorSession);
    const res = await store.dispatch(omsApi.endpoints.getHealth.initiate());
    expect(calls).toBe(3);
    expect(res.error).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });

  it('401 on an API call logs the user out as "expired" and clears cached server data', async () => {
    server.use(http.get(url('/orders'), () => problem(401, 'UNAUTHENTICATED')));
    const store = signedIn();
    await store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    expect(store.getState().auth.session).toBeNull();
    expect(store.getState().auth.logoutReason).toBe('expired');
    await vi.waitFor(() => expect(Object.keys(store.getState().omsApi.queries)).toHaveLength(0)); // resetApiState
  });

  it('401 from the token endpoint (bad password) is NOT treated as an expired session', async () => {
    server.use(http.post(url('/auth/token'), () => problem(401, 'UNAUTHENTICATED')));
    const store = signedIn();
    const res = await store.dispatch(omsApi.endpoints.login.initiate({ username: 'x', password: 'y' }));
    expect(res.error).toMatchObject({ status: 401 });
    expect(store.getState().auth.session).not.toBeNull();
  });

  it('401 while signed out does not dispatch a logout', async () => {
    server.use(http.get(url('/orders'), () => problem(401, 'UNAUTHENTICATED')));
    const store = createAppStore({ storage: null, preloadedAuth: { session: null, logoutReason: null } });
    await store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    expect(store.getState().auth.logoutReason).toBeNull();
  });

  it('429 raises a warning toast that includes the Retry-After wait', async () => {
    server.use(http.get(url('/orders'), () => HttpResponse.json({ code: 'RATE_LIMITED', status: 429 }, { status: 429, headers: { 'Retry-After': '9', 'Content-Type': 'application/problem+json' } })));
    const store = signedIn();
    await store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    const t = store.getState().ui.toasts[0];
    expect(t.severity).toBe('warning');
    expect(t.message).toContain('Try again in 9s');
  });

  it('a body that is not JSON still yields a usable error', async () => {
    server.use(http.get(url('/orders'), () => new HttpResponse('<html>Bad gateway</html>', { status: 500, headers: { 'Content-Type': 'text/html' } })));
    const store = signedIn();
    const res = await store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    expect(res.error).toMatchObject({ status: 500, code: 'HTTP_500' });
  });
});

describe('endpoints', () => {
  it('createOrder sends Idempotency-Key and exposes replay + location', async () => {
    let key: string | null = null;
    server.use(
      http.post(url('/orders'), async ({ request }) => {
        key = request.headers.get('idempotency-key');
        return HttpResponse.json(makeOrder({ status: 'CREATED' }), { status: 202, headers: { 'Idempotent-Replay': 'true', Location: `/api/v1/orders/${ORDER_ID}` } });
      }),
    );
    const store = signedIn();
    const res = await store.dispatch(
      omsApi.endpoints.createOrder.initiate({ body: { customerId: 'c', items: [{ productId: 'p', quantity: 1 }], shippingAddress: { line1: 'a', city: 'b', postalCode: '123', country: 'US' } }, key: 'oms-key-98765' }),
    );
    expect(key).toBe('oms-key-98765');
    expect('data' in res && res.data).toMatchObject({ replay: true, location: `/api/v1/orders/${ORDER_ID}` });
  });

  it('getOrderSnapshot tolerates a failing saga call (staff) and returns order + history', async () => {
    server.use(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder())),
      http.get(url('/orders/:id/history'), () => HttpResponse.json([])),
      http.get(url('/admin/saga/:id'), () => problem(500, 'INTERNAL_ERROR')),
    );
    const store = signedIn(operatorSession);
    const res = await store.dispatch(omsApi.endpoints.getOrderSnapshot.initiate({ id: ORDER_ID, withSaga: true }));
    expect(res.data?.order.id).toBe(ORDER_ID);
    expect(res.data?.saga).toBeNull();
  });

  it('getOrderSnapshot fails when the history call fails', async () => {
    server.use(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder())),
      http.get(url('/orders/:id/history'), () => problem(404, 'NOT_FOUND')),
    );
    const store = signedIn();
    const res = await store.dispatch(omsApi.endpoints.getOrderSnapshot.initiate({ id: ORDER_ID, withSaga: false }));
    expect(res.error).toMatchObject({ status: 404 });
  });

  it('getInvestigation combines order, history, saga and audit, blanking only failed optional parts', async () => {
    server.use(
      http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder({ status: 'FAILED' }))),
      http.get(url('/orders/:id/history'), () => HttpResponse.json([])),
      http.get(url('/admin/saga/:id'), () => problem(404, 'NOT_FOUND')),
      http.get(url('/ops/order/audit'), ({ request }) => {
        const q = new URL(request.url).searchParams;
        expect(q.get('entityType')).toBe('Order');
        expect(q.get('entityId')).toBe(ORDER_ID);
        return HttpResponse.json(page([{ id: 'a', entityType: 'Order', entityId: ORDER_ID, action: 'X', actor: null, correlationId: null, details: null, occurredAt: '2026-01-01T00:00:00Z' }]));
      }),
    );
    const store = signedIn(operatorSession);
    const ok = await store.dispatch(omsApi.endpoints.getInvestigation.initiate(ORDER_ID));
    expect(ok.data?.saga).toBeNull();
    expect(ok.data?.audit).toHaveLength(1);

    server.use(http.get(url('/ops/order/audit'), () => problem(403, 'FORBIDDEN')));
    const store2 = signedIn(operatorSession);
    const degraded = await store2.dispatch(omsApi.endpoints.getInvestigation.initiate(ORDER_ID));
    expect(degraded.data?.audit).toEqual([]);

    server.use(http.get(url('/orders/:id'), () => problem(404, 'NOT_FOUND')));
    const store3 = signedIn(operatorSession);
    expect((await store3.dispatch(omsApi.endpoints.getInvestigation.initiate(ORDER_ID))).error).toMatchObject({ status: 404 });
  });

  it('mutations invalidate cached lists (cancel -> Orders refetched)', async () => {
    let lists = 0;
    server.use(
      http.get(url('/orders'), () => (lists++, HttpResponse.json(page([])))),
      http.post(url('/orders/:id/cancel'), () => HttpResponse.json(makeOrder({ status: 'CANCELLED' }))),
    );
    const store = signedIn();
    const sub = store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    await sub;
    expect(lists).toBe(1);
    await store.dispatch(omsApi.endpoints.cancelOrder.initiate({ id: ORDER_ID, reason: 'because' }));
    await vi.waitFor(() => expect(lists).toBe(2));
    sub.unsubscribe();
  });

  it('logout action resets the API cache', async () => {
    server.use(http.get(url('/orders'), () => HttpResponse.json(page([]))));
    const store = signedIn();
    const sub = store.dispatch(omsApi.endpoints.listOrders.initiate({}));
    await sub;
    expect(Object.keys(store.getState().omsApi.queries).length).toBeGreaterThan(0);
    store.dispatch(loggedOut('user'));
    await vi.waitFor(() => expect(Object.keys(store.getState().omsApi.queries)).toHaveLength(0));
  });
});

describe('store persistence', () => {
  const fakeStorage = () => {
    const m = new Map<string, string>();
    return { m, storage: { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), clear: () => m.clear(), key: () => null, length: 0 } as unknown as Storage };
  };

  it('mirrors login to sessionStorage, restores it in a new store, and clears it on logout', async () => {
    const { m, storage } = fakeStorage();
    server.use(
      http.post(url('/auth/token'), () => HttpResponse.json({ accessToken: 'tok-x', tokenType: 'Bearer', expiresIn: 3600, username: 'operator1', roles: ['OPERATOR'], customerId: null })),
    );
    const store = createAppStore({ storage });
    expect(store.getState().auth.session).toBeNull();
    const { loginSucceeded } = await import('./authSlice');
    const res = await store.dispatch(omsApi.endpoints.login.initiate({ username: 'operator1', password: 'pw' }));
    store.dispatch(loginSucceeded(res.data!));
    expect(m.get('oms.session')).toContain('tok-x');
    expect(JSON.stringify(res.data)).toContain('tok-x');

    const reloaded = createAppStore({ storage });
    expect(reloaded.getState().auth.session?.username).toBe('operator1');

    store.dispatch(loggedOut('user'));
    expect(m.has('oms.session')).toBe(false);
  });
});

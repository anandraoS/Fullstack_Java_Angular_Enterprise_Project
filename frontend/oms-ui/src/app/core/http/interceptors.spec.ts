import { HttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { AuthStore } from '../auth/auth.store';
import { Toaster } from '../notify/toaster';
import { API, httpTestProviders, makeSession } from '../../testing/test-utils';
import { ApiError } from './api-error';
import { isTransient } from './interceptors';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let mock: HttpTestingController;
  let auth: AuthStore;
  let toaster: jasmine.SpyObj<Toaster>;

  beforeEach(() => {
    sessionStorage.clear();
    toaster = jasmine.createSpyObj<Toaster>('Toaster', ['warn', 'error', 'success', 'info']);
    TestBed.configureTestingModule({ providers: httpTestProviders([{ provide: Toaster, useValue: toaster }]) });
    http = TestBed.inject(HttpClient);
    mock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
  });

  afterEach(() => {
    mock.verify();
    sessionStorage.clear();
  });

  function signIn(): void {
    sessionStorage.setItem('oms.session', JSON.stringify(makeSession()));
    // re-create the store state from storage
    (auth as unknown as { setSession(s: unknown, p: boolean): void }).setSession(makeSession(), false);
  }

  it('adds a bearer token and a correlation id to API calls', () => {
    signIn();
    http.get(`${API}/orders`).subscribe();
    const req = mock.expectOne(`${API}/orders`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');
    expect(req.request.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
    req.flush({});
  });

  it('does not attach the token to the token endpoint or to foreign origins', () => {
    signIn();
    http.post(`${API}/auth/token`, {}).subscribe();
    expect(mock.expectOne(`${API}/auth/token`).request.headers.has('Authorization')).toBeFalse();
    http.get('https://example.test/other').subscribe();
    const other = mock.expectOne('https://example.test/other');
    expect(other.request.headers.has('Authorization')).toBeFalse();
    expect(other.request.headers.has('X-Correlation-Id')).toBeFalse();
  });

  it('keeps a caller supplied correlation id', () => {
    http.get(`${API}/orders`, { headers: { 'X-Correlation-Id': 'mine' } }).subscribe();
    const req = mock.expectOne(`${API}/orders`);
    expect(req.request.headers.get('X-Correlation-Id')).toBe('mine');
    req.flush({});
  });

  it('normalizes errors to ApiError', () => {
    let caught: unknown;
    http.get(`${API}/orders/x`).subscribe({ error: (e) => (caught = e) });
    mock.expectOne(`${API}/orders/x`).flush({ code: 'NOT_FOUND', correlationId: 'c9' }, { status: 404, statusText: 'Not Found' });
    expect(caught instanceof ApiError).toBeTrue();
    expect((caught as ApiError).code).toBe('NOT_FOUND');
    expect((caught as ApiError).correlationId).toBe('c9');
  });

  it('logs out on 401 for API calls', () => {
    signIn();
    const spy = spyOn(auth, 'logout');
    http.get(`${API}/orders`).subscribe({ error: () => undefined });
    mock.expectOne(`${API}/orders`).flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    expect(spy).toHaveBeenCalledWith('expired');
  });

  it('does not log out when the login call itself returns 401', () => {
    signIn();
    const spy = spyOn(auth, 'logout');
    http.post(`${API}/auth/token`, {}).subscribe({ error: () => undefined });
    mock.expectOne(`${API}/auth/token`).flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    expect(spy).not.toHaveBeenCalled();
  });

  it('shows a toast with the wait time on 429 and does not retry it', () => {
    http.get(`${API}/products`).subscribe({ error: () => undefined });
    mock
      .expectOne(`${API}/products`)
      .flush({ code: 'RATE_LIMITED' }, { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '3' } });
    expect(toaster.warn).toHaveBeenCalled();
    expect(toaster.warn.calls.mostRecent().args[0]).toContain('3s');
    mock.expectNone(`${API}/products`);
  });

  it('retries idempotent GETs on transient failures with backoff', fakeAsync(() => {
    let result: unknown;
    http.get(`${API}/products`).subscribe((r) => (result = r));
    mock.expectOne(`${API}/products`).flush(null, { status: 503, statusText: 'Unavailable' });
    tick(200);
    mock.expectOne(`${API}/products`).flush(null, { status: 502, statusText: 'Bad Gateway' });
    tick(400);
    mock.expectOne(`${API}/products`).flush({ ok: true });
    expect(result).toEqual({ ok: true });
  }));

  it('gives up after two retries', fakeAsync(() => {
    let caught: unknown;
    http.get(`${API}/products`).subscribe({ error: (e) => (caught = e) });
    mock.expectOne(`${API}/products`).flush(null, { status: 503, statusText: 'x' });
    tick(200);
    mock.expectOne(`${API}/products`).flush(null, { status: 503, statusText: 'x' });
    tick(400);
    mock.expectOne(`${API}/products`).flush(null, { status: 503, statusText: 'x' });
    expect((caught as ApiError).status).toBe(503);
  }));

  it('never retries non-idempotent requests', () => {
    let caught: unknown;
    http.post(`${API}/orders`, {}).subscribe({ error: (e) => (caught = e) });
    mock.expectOne(`${API}/orders`).flush(null, { status: 503, statusText: 'x' });
    expect((caught as ApiError).status).toBe(503);
    mock.expectNone(`${API}/orders`);
  });

  it('does not retry client errors on GET', () => {
    http.get(`${API}/orders/1`).subscribe({ error: () => undefined });
    mock.expectOne(`${API}/orders/1`).flush(null, { status: 404, statusText: 'x' });
    mock.expectNone(`${API}/orders/1`);
  });

  it('classifies transient statuses', () => {
    expect(isTransient(new ApiError(0, 'NETWORK_ERROR', '', null, '', null))).toBeTrue();
    expect(isTransient(new ApiError(503, 'X', '', null, '', null))).toBeTrue();
    expect(isTransient(new ApiError(500, 'X', '', null, '', null))).toBeFalse();
    expect(isTransient(new ApiError(429, 'X', '', null, '', null))).toBeFalse();
  });
});

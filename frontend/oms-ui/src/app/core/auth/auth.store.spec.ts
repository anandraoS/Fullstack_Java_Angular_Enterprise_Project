import { HttpTestingController } from '@angular/common/http/testing';
import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { Router } from '@angular/router';
import { API, httpTestProviders, makeSession } from '../../testing/test-utils';
import { TokenResponse } from '../models';
import { AuthStore, SESSION_STORAGE_KEY, hasAnyRole, parseStoredSession, sessionFromToken } from './auth.store';

const TOKEN: TokenResponse = {
  accessToken: 'jwt-value',
  tokenType: 'Bearer',
  expiresIn: 3600,
  username: 'admin1',
  roles: ['ADMIN'],
  customerId: null,
};

describe('auth pure functions', () => {
  it('sessionFromToken computes expiry', () => {
    expect(sessionFromToken(TOKEN, 1000).expiresAt).toBe(1000 + 3_600_000);
  });

  it('hasAnyRole', () => {
    const s = makeSession({ roles: ['CUSTOMER'] });
    expect(hasAnyRole(s, ['OPERATOR', 'ADMIN'])).toBeFalse();
    expect(hasAnyRole(s, ['CUSTOMER'])).toBeTrue();
    expect(hasAnyRole(s, undefined)).toBeTrue();
    expect(hasAnyRole(null, undefined)).toBeFalse();
  });

  it('parseStoredSession rejects malformed and expired data', () => {
    expect(parseStoredSession(null, 0)).toBeNull();
    expect(parseStoredSession('{not json', 0)).toBeNull();
    expect(parseStoredSession(JSON.stringify({ accessToken: 'a' }), 0)).toBeNull();
    expect(parseStoredSession(JSON.stringify(makeSession({ expiresAt: 5 })), 10)).toBeNull();
    expect(parseStoredSession(JSON.stringify(makeSession({ expiresAt: 50 })), 10)?.username).toBe('operator1');
  });
});

describe('AuthStore', () => {
  let mock: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: httpTestProviders() });
    mock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => sessionStorage.clear());

  it('logs in, exposes role signals and mirrors the session to sessionStorage', () => {
    const store = TestBed.inject(AuthStore);
    expect(store.isAuthenticated()).toBeFalse();
    store.login('admin1', 'pw').subscribe();
    const req = mock.expectOne(`${API}/auth/token`);
    expect(req.request.body).toEqual({ username: 'admin1', password: 'pw' });
    req.flush(TOKEN);
    expect(store.isAuthenticated()).toBeTrue();
    expect(store.isAdmin()).toBeTrue();
    expect(store.isStaff()).toBeTrue();
    expect(store.isCustomer()).toBeFalse();
    expect(store.token()).toBe('jwt-value');
    const saved = JSON.parse(sessionStorage.getItem(SESSION_STORAGE_KEY) as string);
    expect(saved.username).toBe('admin1');
    store.logout();
  });

  it('does not persist the password anywhere', () => {
    const store = TestBed.inject(AuthStore);
    store.login('admin1', 'super-secret-pw').subscribe();
    mock.expectOne(`${API}/auth/token`).flush(TOKEN);
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).not.toContain('super-secret-pw');
    store.logout();
  });

  it('restores a valid session on construction', () => {
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(makeSession({ roles: ['CUSTOMER'], customerId: 'c1' })));
    const store = TestBed.inject(AuthStore);
    expect(store.isAuthenticated()).toBeTrue();
    expect(store.isCustomer()).toBeTrue();
    expect(store.customerId()).toBe('c1');
  });

  it('logout clears state and storage and navigates to login', () => {
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(makeSession()));
    const store = TestBed.inject(AuthStore);
    const nav = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    store.logout('expired');
    expect(store.isAuthenticated()).toBeFalse();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(nav).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'expired' } });
  });

  it('logs out automatically when the token expires', fakeAsync(() => {
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(makeSession({ expiresAt: Date.now() + 1000 })));
    const store = TestBed.inject(AuthStore);
    const nav = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    expect(store.isAuthenticated()).toBeTrue();
    tick(1500);
    expect(store.isAuthenticated()).toBeFalse();
    expect(nav).toHaveBeenCalled();
  }));
});

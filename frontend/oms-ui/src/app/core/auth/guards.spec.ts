import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { makeSession } from '../../testing/test-utils';
import { SESSION_STORAGE_KEY } from './auth.store';
import { authGuard, guestGuard, roleGuard } from './guards';

describe('route guards (UX only)', () => {
  function setup(session?: ReturnType<typeof makeSession>) {
    sessionStorage.clear();
    if (session) sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    return TestBed.inject(Router);
  }
  const route = (roles?: string[]) => ({ data: { roles } }) as unknown as ActivatedRouteSnapshot;
  const state = { url: '/orders/abc' } as RouterStateSnapshot;
  const run = (guard: typeof authGuard, r: ActivatedRouteSnapshot) => TestBed.runInInjectionContext(() => guard(r, state));

  afterEach(() => sessionStorage.clear());

  it('authGuard redirects anonymous users to login with returnUrl', () => {
    const router = setup();
    const result = run(authGuard, route()) as UrlTree;
    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Forders%2Fabc');
  });

  it('authGuard lets authenticated users through', () => {
    setup(makeSession());
    expect(run(authGuard, route())).toBeTrue();
  });

  it('roleGuard allows a matching role', () => {
    setup(makeSession({ roles: ['OPERATOR'] }));
    expect(run(roleGuard, route(['OPERATOR', 'ADMIN']))).toBeTrue();
  });

  it('roleGuard redirects a customer away from staff screens', () => {
    const router = setup(makeSession({ roles: ['CUSTOMER'], username: 'customer1' }));
    const result = run(roleGuard, route(['OPERATOR', 'ADMIN'])) as UrlTree;
    expect(router.serializeUrl(result)).toBe('/forbidden');
  });

  it('roleGuard sends anonymous users to login', () => {
    const router = setup();
    expect(router.serializeUrl(run(roleGuard, route(['ADMIN'])) as UrlTree)).toBe('/login');
  });

  it('guestGuard bounces signed-in users to the dashboard', () => {
    const router = setup(makeSession());
    expect(router.serializeUrl(run(guestGuard, route()) as UrlTree)).toBe('/dashboard');
  });
});

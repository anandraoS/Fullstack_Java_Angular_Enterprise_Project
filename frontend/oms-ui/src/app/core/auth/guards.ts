import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../models';
import { AuthStore } from './auth.store';

/**
 * Route guards are a UX convenience only (hide screens the user cannot use). The backend is the sole authority:
 * every endpoint re-checks roles and ownership, and a tampered client gains nothing.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  if (auth.isAuthenticated()) return true;
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  if (!auth.isAuthenticated()) return router.createUrlTree(['/login']);
  const wanted = route.data['roles'] as Role[] | undefined;
  return auth.hasAnyRole(wanted) ? true : router.createUrlTree(['/forbidden']);
};

/** Keeps signed-in users away from the login page. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  return auth.isAuthenticated() ? router.createUrlTree(['/dashboard']) : true;
};

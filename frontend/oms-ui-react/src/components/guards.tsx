import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router-dom';
import { useAppSelector } from '../hooks/redux';
import type { Role } from '../models';
import { selectIsAuthenticated, selectLogoutReason, selectSession, hasAnyRole } from '../store/authSlice';

export const STAFF: Role[] = ['OPERATOR', 'ADMIN'];

/** Only same-app relative paths are honoured for returnUrl (prevents open redirects). */
export function safeReturnUrl(url: string | undefined | null): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.includes('://') && !url.includes('\\') ? url : '/dashboard';
}

/**
 * Route guards are a UX convenience only (hide screens the user cannot use). The backend is the sole authority: every
 * endpoint re-checks roles and ownership, and a tampered client gains nothing.
 */
export function RequireAuth() {
  const authed = useAppSelector(selectIsAuthenticated);
  const reason = useAppSelector(selectLogoutReason);
  const loc = useLocation();
  if (authed) return <Outlet />;
  const to = reason === 'expired' ? '/login?reason=expired' : reason === 'user' ? '/login' : `/login?returnUrl=${encodeURIComponent(loc.pathname + loc.search)}`;
  return <Navigate to={to} replace />;
}

export function RequireRole({ roles }: { roles: readonly Role[] }) {
  const session = useAppSelector(selectSession);
  return hasAnyRole(session, roles) ? <Outlet /> : <Navigate to="/forbidden" replace />;
}

/** Keeps signed-in users away from the login page (honouring a safe returnUrl right after sign-in). */
export function GuestOnly() {
  const authed = useAppSelector(selectIsAuthenticated);
  const [params] = useSearchParams();
  return authed ? <Navigate to={safeReturnUrl(params.get('returnUrl'))} replace /> : <Outlet />;
}

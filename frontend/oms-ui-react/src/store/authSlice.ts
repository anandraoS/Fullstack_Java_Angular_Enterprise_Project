import { createSelector, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import type { Role, Session, TokenResponse } from '../models';

export const SESSION_STORAGE_KEY = 'oms.session';
const MAX_TIMER_MS = 2_147_483_000;

export type LogoutReason = 'user' | 'expired';

export interface AuthState {
  session: Session | null;
  /** why the last logout happened (drives the login-page notice and the guard redirect) */
  logoutReason: LogoutReason | null;
}

/** Pure: builds a Session from the token endpoint response. */
export function sessionFromToken(res: TokenResponse, nowMs: number): Session {
  return {
    accessToken: res.accessToken,
    username: res.username,
    roles: res.roles,
    customerId: res.customerId ?? null,
    expiresAt: nowMs + res.expiresIn * 1000,
  };
}

/** Pure: does the session hold at least one of the wanted roles? Empty `wanted` means any authenticated user. */
export function hasAnyRole(session: Session | null, wanted: readonly Role[] | undefined): boolean {
  if (!session) return false;
  if (!wanted || wanted.length === 0) return true;
  return session.roles.some((r) => wanted.includes(r));
}

/** Pure: parse + validate a stored session; returns null when missing, malformed or expired. */
export function parseStoredSession(raw: string | null, nowMs: number): Session | null {
  if (!raw) return null;
  try {
    const s = JSON.parse(raw) as Partial<Session>;
    if (
      typeof s.accessToken === 'string' &&
      typeof s.username === 'string' &&
      Array.isArray(s.roles) &&
      typeof s.expiresAt === 'number' &&
      s.expiresAt > nowMs
    ) {
      return {
        accessToken: s.accessToken,
        username: s.username,
        roles: s.roles,
        customerId: s.customerId ?? null,
        expiresAt: s.expiresAt,
      };
    }
  } catch {
    // fall through: malformed
  }
  return null;
}

/** Milliseconds until the session expires, clamped to what setTimeout can hold. */
export function msUntilExpiry(expiresAt: number, nowMs: number): number {
  return Math.min(Math.max(expiresAt - nowMs, 0), MAX_TIMER_MS);
}

export function readStoredSession(storage: Pick<Storage, 'getItem'> | null, nowMs: number): Session | null {
  try {
    return parseStoredSession(storage?.getItem(SESSION_STORAGE_KEY) ?? null, nowMs);
  } catch {
    return null; // storage unavailable (private mode)
  }
}

export function writeStoredSession(storage: Pick<Storage, 'setItem' | 'removeItem'> | null, session: Session | null): void {
  try {
    if (session) storage?.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
    else storage?.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // in-memory session still works
  }
}

/**
 * Token storage trade-off: the token lives in Redux (memory) and is mirrored to sessionStorage (see store/index.ts) so a
 * reload does not log the user out. sessionStorage is per tab and cleared on close, but readable by any script on the
 * origin (XSS). Production should use authorization-code + PKCE with a BFF and HttpOnly cookies.
 */
export const authSlice = createSlice({
  name: 'auth',
  initialState: { session: null, logoutReason: null } as AuthState,
  reducers: {
    sessionStarted(state, action: PayloadAction<Session>) {
      state.session = action.payload;
      state.logoutReason = null;
    },
    /** token response -> session (prepare() computes the absolute expiry, keeping the reducer pure) */
    loginSucceeded: {
      reducer(state, action: PayloadAction<Session>) {
        state.session = action.payload;
        state.logoutReason = null;
      },
      prepare(res: TokenResponse, nowMs: number = Date.now()) {
        return { payload: sessionFromToken(res, nowMs) };
      },
    },
    loggedOut(state, action: PayloadAction<LogoutReason | undefined>) {
      state.session = null;
      state.logoutReason = action.payload ?? 'user';
    },
  },
});

export const { sessionStarted, loginSucceeded, loggedOut } = authSlice.actions;

interface WithAuth {
  auth: AuthState;
}

export const selectSession = (s: WithAuth) => s.auth.session;
export const selectLogoutReason = (s: WithAuth) => s.auth.logoutReason;
export const selectIsAuthenticated = (s: WithAuth) => s.auth.session !== null;
export const selectToken = (s: WithAuth) => s.auth.session?.accessToken ?? null;
export const selectUsername = (s: WithAuth) => s.auth.session?.username ?? '';
export const selectCustomerId = (s: WithAuth) => s.auth.session?.customerId ?? null;
const EMPTY_ROLES: Role[] = [];
export const selectRoles = (s: WithAuth): Role[] => s.auth.session?.roles ?? EMPTY_ROLES;
export const selectIsStaff = createSelector(selectRoles, (r) => r.some((x) => x === 'OPERATOR' || x === 'ADMIN'));
export const selectIsAdmin = createSelector(selectRoles, (r) => r.includes('ADMIN'));
export const selectIsCustomer = createSelector(selectRoles, selectIsStaff, (r, staff) => r.includes('CUSTOMER') && !staff);

export default authSlice.reducer;

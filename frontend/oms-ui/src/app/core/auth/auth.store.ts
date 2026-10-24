import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { ConfigService } from '../config.service';
import { Role, Session, TokenResponse } from '../models';

export const SESSION_STORAGE_KEY = 'oms.session';
const MAX_TIMER_MS = 2_147_483_000;

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
      return { accessToken: s.accessToken, username: s.username, roles: s.roles, customerId: s.customerId ?? null, expiresAt: s.expiresAt };
    }
  } catch {
    // fall through
  }
  return null;
}

/**
 * Signal store for authentication state.
 *
 * Token storage trade-off: the token lives in a signal (memory) and is mirrored to sessionStorage so a page reload
 * does not log the user out. sessionStorage is per-tab and cleared when the tab closes, but it is readable by any
 * script on the origin (XSS). A production deployment should prefer an OIDC authorization-code + PKCE flow with a
 * BFF holding tokens in HttpOnly cookies; this dev flow trades that security for simplicity. A strict CSP (see
 * nginx.conf) reduces, but does not remove, the XSS risk.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ConfigService);
  private readonly router = inject(Router);

  private readonly _session = signal<Session | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly session = this._session.asReadonly();
  readonly isAuthenticated = computed(() => this._session() !== null);
  readonly roles = computed(() => this._session()?.roles ?? []);
  readonly username = computed(() => this._session()?.username ?? '');
  readonly customerId = computed(() => this._session()?.customerId ?? null);
  readonly isStaff = computed(() => this.roles().some((r) => r === 'OPERATOR' || r === 'ADMIN'));
  readonly isAdmin = computed(() => this.roles().includes('ADMIN'));
  readonly isCustomer = computed(() => this.roles().includes('CUSTOMER') && !this.isStaff());
  readonly token = computed(() => this._session()?.accessToken ?? null);

  constructor() {
    this.setSession(parseStoredSession(this.read(), Date.now()), false);
  }

  hasAnyRole(wanted: readonly Role[] | undefined): boolean {
    return hasAnyRole(this._session(), wanted);
  }

  login(username: string, password: string): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.config.apiBaseUrl}/auth/token`, { username, password })
      .pipe(tap((res) => this.setSession(sessionFromToken(res, Date.now()), true)));
  }

  logout(reason: 'user' | 'expired' = 'user'): void {
    this.setSession(null, true);
    void this.router.navigate(['/login'], { queryParams: reason === 'expired' ? { reason } : {} });
  }

  private setSession(session: Session | null, persist: boolean): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this._session.set(session);
    if (persist) this.write(session);
    if (session) {
      const ms = Math.min(Math.max(session.expiresAt - Date.now(), 0), MAX_TIMER_MS);
      this.timer = setTimeout(() => this.logout('expired'), ms);
    }
  }

  private read(): string | null {
    try {
      return sessionStorage.getItem(SESSION_STORAGE_KEY);
    } catch {
      return null;
    }
  }

  private write(session: Session | null): void {
    try {
      if (session) sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
      else sessionStorage.removeItem(SESSION_STORAGE_KEY);
    } catch {
      // storage unavailable (private mode): in-memory session still works
    }
  }
}

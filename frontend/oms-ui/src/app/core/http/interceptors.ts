import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, retry, throwError, timer } from 'rxjs';
import { AuthStore } from '../auth/auth.store';
import { ConfigService } from '../config.service';
import { Toaster } from '../notify/toaster';
import { ApiError, normalizeError } from './api-error';

export const CORRELATION_HEADER = 'X-Correlation-Id';
export const MAX_GET_RETRIES = 2;

export function newId(): string {
  return crypto.randomUUID();
}

function isApiCall(url: string, apiBase: string): boolean {
  return url.startsWith(apiBase + '/') || url === apiBase;
}

function isTokenCall(url: string): boolean {
  return url.endsWith('/auth/token');
}

/** Adds X-Correlation-Id (unless the caller supplied one) so UI actions can be traced through gateway and services. */
export const correlationInterceptor: HttpInterceptorFn = (req, next) => {
  const apiBase = inject(ConfigService).apiBaseUrl;
  if (!isApiCall(req.url, apiBase) || req.headers.has(CORRELATION_HEADER)) return next(req);
  return next(req.clone({ setHeaders: { [CORRELATION_HEADER]: newId() } }));
};

/** Adds the bearer token to API calls (never to the token endpoint, never to foreign origins). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const apiBase = inject(ConfigService).apiBaseUrl;
  const token = inject(AuthStore).token();
  if (!token || !isApiCall(req.url, apiBase) || isTokenCall(req.url)) return next(req);
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};

/** Transient failures worth retrying for idempotent reads: network error, 502, 503, 504. */
export function isTransient(err: unknown): boolean {
  const status = err instanceof ApiError ? err.status : err instanceof HttpErrorResponse ? err.status : -1;
  return status === 0 || status === 502 || status === 503 || status === 504;
}

/**
 * Retries ONLY idempotent GET/HEAD requests, with exponential backoff (200ms, 400ms). POST/PUT/DELETE are never
 * retried here: unsafe retries are the caller's decision (orders use an Idempotency-Key for that).
 * Registered before the error interceptor so it sees normalized ApiErrors.
 */
export const retryInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') return next(req);
  return next(req).pipe(
    retry({
      count: MAX_GET_RETRIES,
      delay: (err, attempt) => (isTransient(err) ? timer(200 * 2 ** (attempt - 1)) : throwError(() => err)),
    }),
  );
};

/** Normalizes every failure to ApiError, logs out on 401 (except on login), and notifies on 429. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthStore);
  const toaster = inject(Toaster);
  const apiBase = inject(ConfigService).apiBaseUrl;
  return next(req).pipe(
    catchError((err: unknown) => {
      const apiErr = normalizeError(err);
      if (isApiCall(req.url, apiBase)) {
        if (apiErr.status === 401 && !isTokenCall(req.url) && auth.isAuthenticated()) {
          auth.logout('expired');
        } else if (apiErr.status === 429) {
          const wait = apiErr.retryAfterSeconds ? ` Try again in ${apiErr.retryAfterSeconds}s.` : '';
          toaster.warn(`${apiErr.userMessage}${wait}`);
        }
      }
      return throwError(() => apiErr);
    }),
  );
};

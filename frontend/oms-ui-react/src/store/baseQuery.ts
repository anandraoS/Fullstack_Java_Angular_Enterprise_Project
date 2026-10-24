import { fetchBaseQuery, type BaseQueryFn, type FetchArgs, type FetchBaseQueryError, type FetchBaseQueryMeta } from '@reduxjs/toolkit/query';
import { normalizeFailure, type ApiError } from '../core/apiError';
import { apiBaseUrl } from '../core/config';
import { loggedOut, type AuthState } from './authSlice';
import { toast } from './uiSlice';

export const CORRELATION_HEADER = 'X-Correlation-Id';
export const MAX_GET_RETRIES = 2;

let retryBaseDelayMs = 200;
/** Tests set this to 0 so retries do not slow the suite down. */
export function setRetryBaseDelay(ms: number): void {
  retryBaseDelayMs = ms;
}

export function newId(): string {
  return crypto.randomUUID();
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** JSON body when there is one (also for application/problem+json), otherwise text/null. Never throws. */
async function lenientResponseHandler(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

interface WithAuth {
  auth: AuthState;
}

const isTokenCall = (url: string) => url.endsWith('/auth/token');

type Raw = BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError, object, FetchBaseQueryMeta>;

/** fetchBaseQuery whose baseUrl is read at call time (runtime config) and which adds correlation + bearer headers. */
const rawBaseQuery: Raw = (args, api, extra) => {
  const inner = fetchBaseQuery({
    baseUrl: apiBaseUrl(),
    responseHandler: lenientResponseHandler,
    prepareHeaders: (headers, { getState, endpoint }) => {
      if (!headers.has(CORRELATION_HEADER)) headers.set(CORRELATION_HEADER, newId());
      const token = (getState() as WithAuth).auth.session?.accessToken;
      // Bearer token on API calls only: never on the token endpoint.
      if (token && endpoint !== 'login') headers.set('Authorization', `Bearer ${token}`);
      return headers;
    },
  });
  return inner(args, api, extra);
};

/** Transient failures worth retrying for idempotent reads: network error, 502, 503, 504. */
export function isTransientStatus(status: number): boolean {
  return status === 0 || status === 502 || status === 503 || status === 504;
}

function statusOf(err: FetchBaseQueryError): number {
  if (typeof err.status === 'number') return err.status;
  if (err.status === 'PARSING_ERROR') return err.originalStatus;
  if (err.status === 'CUSTOM_ERROR') return -1;
  return 0; // FETCH_ERROR, TIMEOUT_ERROR
}

/**
 * RTK Query base query for the whole app. It is the React/Redux counterpart of the Angular interceptor chain
 * (correlation -> auth -> retry -> error):
 *  - correlation + bearer headers (prepareHeaders above),
 *  - GET/HEAD only are retried (2x, 200ms/400ms) for transient failures, writes never,
 *  - every failure becomes a serializable ApiError (problem+json aware),
 *  - 401 outside the login call logs out ("expired"), 429 raises a toast with the wait time.
 */
export const omsBaseQuery: BaseQueryFn<string | FetchArgs, unknown, ApiError, object, FetchBaseQueryMeta> = async (args, api, extra) => {
  const method = (typeof args === 'string' ? 'GET' : (args.method ?? 'GET')).toUpperCase();
  const url = typeof args === 'string' ? args : args.url;
  const idempotent = method === 'GET' || method === 'HEAD';

  let result = await rawBaseQuery(args, api, extra);
  for (let attempt = 1; idempotent && result.error && attempt <= MAX_GET_RETRIES && isTransientStatus(statusOf(result.error)); attempt++) {
    await sleep(retryBaseDelayMs * 2 ** (attempt - 1));
    result = await rawBaseQuery(args, api, extra);
  }

  if (!result.error) return { data: result.data, meta: result.meta };

  const err = normalizeFailure({
    status: statusOf(result.error),
    body: 'data' in result.error ? result.error.data : undefined,
    statusText: result.meta?.response?.statusText,
    retryAfter: result.meta?.response?.headers.get('Retry-After'),
    correlationHeader: result.meta?.response?.headers.get(CORRELATION_HEADER),
  });

  const signedIn = (api.getState() as WithAuth).auth.session !== null;
  if (err.status === 401 && !isTokenCall(url) && signedIn) {
    api.dispatch(loggedOut('expired'));
  } else if (err.status === 429) {
    const wait = err.retryAfterSeconds ? ` Try again in ${err.retryAfterSeconds}s.` : '';
    api.dispatch(toast.warn(`${err.userMessage}${wait}`));
  }
  return { error: err, meta: result.meta };
};

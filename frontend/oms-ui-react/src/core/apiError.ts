import type { ProblemDetail } from '../models';

/**
 * Normalized, SERIALIZABLE error (plain object, so it can live in the Redux store and RTK Query cache).
 * Mirrors the Angular ApiError class.
 */
export interface ApiError {
  status: number; // 0 = network error, -1 = client error
  code: string;
  title: string;
  detail: string | null;
  userMessage: string;
  correlationId: string | null;
  fieldErrors: { field: string; message: string }[];
  retryAfterSeconds: number | null;
}

const FRIENDLY_BY_CODE: Record<string, string> = {
  VALIDATION_FAILED: 'Some of the submitted values are not valid. Please review the highlighted fields.',
  NOT_FOUND: 'We could not find what you were looking for.',
  FORBIDDEN: 'You do not have permission to do that.',
  UNAUTHENTICATED: 'Your session has expired. Please sign in again.',
  CONFLICT: 'The item was changed by someone else. Reload and try again.',
  INVALID_STATE_TRANSITION: 'That action is not possible in the current state of the order.',
  INSUFFICIENT_STOCK: 'There is not enough stock to satisfy that request.',
  IDEMPOTENCY_KEY_REUSED: 'This submission conflicts with an earlier one. Please review the form and submit again.',
  IDEMPOTENCY_KEY_REQUIRED: 'The request was missing its idempotency key. Please try again.',
  RATE_LIMITED: 'Too many requests. Please wait a moment and try again.',
  PAYLOAD_TOO_LARGE: 'The request is too large.',
  UPSTREAM_UNAVAILABLE: 'A backend service is temporarily unavailable. Please try again shortly.',
  INTERNAL_ERROR: 'Something went wrong on our side. Please try again; quote the reference below if it persists.',
};

function friendlyByStatus(status: number): string {
  if (status === 0) return 'Cannot reach the server. Check your connection and try again.';
  if (status === 400) return FRIENDLY_BY_CODE.VALIDATION_FAILED;
  if (status === 401) return FRIENDLY_BY_CODE.UNAUTHENTICATED;
  if (status === 403) return FRIENDLY_BY_CODE.FORBIDDEN;
  if (status === 404) return FRIENDLY_BY_CODE.NOT_FOUND;
  if (status === 409) return FRIENDLY_BY_CODE.CONFLICT;
  if (status === 413) return FRIENDLY_BY_CODE.PAYLOAD_TOO_LARGE;
  if (status === 422) return FRIENDLY_BY_CODE.VALIDATION_FAILED;
  if (status === 429) return FRIENDLY_BY_CODE.RATE_LIMITED;
  if (status === 502 || status === 503 || status === 504) return FRIENDLY_BY_CODE.UPSTREAM_UNAVAILABLE;
  return FRIENDLY_BY_CODE.INTERNAL_ERROR;
}

export function isApiError(e: unknown): e is ApiError {
  return typeof e === 'object' && e !== null && 'userMessage' in e && 'status' in e && 'code' in e;
}

export function isNetworkError(e: ApiError): boolean {
  return e.status === 0;
}

/** Minimal view of an HTTP failure that the base query hands to the normalizer (no dependency on fetch types). */
export interface RawFailure {
  status: number;
  body: unknown;
  statusText?: string;
  retryAfter?: string | null;
  correlationHeader?: string | null;
}

/**
 * Converts any failure into an ApiError. Raw server text is never the headline message: the user message comes from
 * a code -> friendly text table. `detail` is kept for diagnostics only.
 */
export function normalizeFailure(f: RawFailure): ApiError {
  const problem: ProblemDetail = typeof f.body === 'object' && f.body !== null ? (f.body as ProblemDetail) : {};
  const code = typeof problem.code === 'string' ? problem.code : f.status === 0 ? 'NETWORK_ERROR' : `HTTP_${f.status}`;
  const retryAfter = f.retryAfter && /^\d+$/.test(f.retryAfter) ? Number(f.retryAfter) : null;
  return {
    status: f.status,
    code,
    title: typeof problem.title === 'string' ? problem.title : (f.statusText ?? 'Error'),
    detail: typeof problem.detail === 'string' ? problem.detail : null,
    userMessage: FRIENDLY_BY_CODE[code] ?? friendlyByStatus(f.status),
    correlationId: typeof problem.correlationId === 'string' ? problem.correlationId : (f.correlationHeader ?? null),
    fieldErrors: Array.isArray(problem.errors) ? problem.errors : [],
    retryAfterSeconds: retryAfter,
  };
}

/** Anything thrown by client code (not HTTP) becomes a generic client error. */
export function normalizeUnknown(err: unknown): ApiError {
  if (isApiError(err)) return err;
  const message = err instanceof Error ? err.message : 'Unexpected error';
  return {
    status: -1,
    code: 'CLIENT_ERROR',
    title: 'Client error',
    detail: message,
    userMessage: FRIENDLY_BY_CODE.INTERNAL_ERROR,
    correlationId: null,
    fieldErrors: [],
    retryAfterSeconds: null,
  };
}

/** Sign-in failures must not reveal whether the username or the password was wrong. */
export function genericSignInError(err: ApiError): ApiError {
  if (err.status === 401 || err.status === 403) {
    return { ...err, detail: null, userMessage: 'Sign-in failed. Check your username and password.' };
  }
  return err;
}

/** Accepts whatever a hook hands back (ApiError, RTK SerializedError, nothing) and returns an ApiError or null. */
export function asApiError(e: unknown): ApiError | null {
  if (e === null || e === undefined) return null;
  return isApiError(e) ? e : normalizeUnknown(e);
}

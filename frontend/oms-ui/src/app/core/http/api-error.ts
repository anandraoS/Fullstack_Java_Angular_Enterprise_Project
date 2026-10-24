import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from '../models';

/** Normalized error that every HTTP failure is converted to by the error interceptor. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly title: string,
    public readonly detail: string | null,
    public readonly userMessage: string,
    public readonly correlationId: string | null,
    public readonly fieldErrors: { field: string; message: string }[] = [],
    public readonly retryAfterSeconds: number | null = null,
  ) {
    super(userMessage);
    this.name = 'ApiError';
  }

  get isNetwork(): boolean {
    return this.status === 0;
  }
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
  if (status === 400) return FRIENDLY_BY_CODE['VALIDATION_FAILED'];
  if (status === 401) return FRIENDLY_BY_CODE['UNAUTHENTICATED'];
  if (status === 403) return FRIENDLY_BY_CODE['FORBIDDEN'];
  if (status === 404) return FRIENDLY_BY_CODE['NOT_FOUND'];
  if (status === 409) return FRIENDLY_BY_CODE['CONFLICT'];
  if (status === 413) return FRIENDLY_BY_CODE['PAYLOAD_TOO_LARGE'];
  if (status === 422) return FRIENDLY_BY_CODE['VALIDATION_FAILED'];
  if (status === 429) return FRIENDLY_BY_CODE['RATE_LIMITED'];
  if (status === 502 || status === 503 || status === 504) return FRIENDLY_BY_CODE['UPSTREAM_UNAVAILABLE'];
  return FRIENDLY_BY_CODE['INTERNAL_ERROR'];
}

function isProblem(body: unknown): body is ProblemDetail {
  return typeof body === 'object' && body !== null;
}

/**
 * Converts anything thrown by HttpClient into an ApiError. Raw server text is never shown to users: the message
 * comes from a code -> friendly text table; `detail` is kept for diagnostics (rendered in a collapsible area).
 */
export function normalizeError(err: unknown): ApiError {
  if (err instanceof ApiError) return err;
  if (err instanceof HttpErrorResponse) {
    const body = err.error;
    const problem: ProblemDetail = isProblem(body) && !(body instanceof Blob) ? body : {};
    const code = problem.code ?? (err.status === 0 ? 'NETWORK_ERROR' : `HTTP_${err.status}`);
    const retryHeader = err.headers?.get('Retry-After');
    const retryAfter = retryHeader && /^\d+$/.test(retryHeader) ? Number(retryHeader) : null;
    const correlationId = problem.correlationId ?? err.headers?.get('X-Correlation-Id') ?? null;
    const message = FRIENDLY_BY_CODE[code] ?? friendlyByStatus(err.status);
    return new ApiError(
      err.status,
      code,
      problem.title ?? err.statusText ?? 'Error',
      typeof problem.detail === 'string' ? problem.detail : null,
      message,
      correlationId,
      Array.isArray(problem.errors) ? problem.errors : [],
      retryAfter,
    );
  }
  const message = err instanceof Error ? err.message : 'Unexpected error';
  return new ApiError(-1, 'CLIENT_ERROR', 'Client error', message, FRIENDLY_BY_CODE['INTERNAL_ERROR'], null);
}

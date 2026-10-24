import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ApiError, normalizeError } from './api-error';

describe('normalizeError', () => {
  it('maps a problem+json body to a friendly ApiError', () => {
    const err = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: {
        code: 'INVALID_STATE_TRANSITION',
        title: 'Invalid state',
        detail: 'Order is FULFILLED and cannot be cancelled (internal text)',
        correlationId: 'abc-123',
      },
    });
    const e = normalizeError(err);
    expect(e instanceof ApiError).toBeTrue();
    expect(e.status).toBe(409);
    expect(e.code).toBe('INVALID_STATE_TRANSITION');
    expect(e.userMessage).toBe('That action is not possible in the current state of the order.');
    expect(e.correlationId).toBe('abc-123');
    expect(e.userMessage).not.toContain('internal text');
    expect(e.detail).toContain('internal text'); // kept for diagnostics, never shown as the headline
  });

  it('keeps field errors from validation problems', () => {
    const e = normalizeError(
      new HttpErrorResponse({
        status: 400,
        error: { code: 'VALIDATION_FAILED', errors: [{ field: 'items[0].quantity', message: 'must be >= 1' }] },
      }),
    );
    expect(e.fieldErrors).toEqual([{ field: 'items[0].quantity', message: 'must be >= 1' }]);
  });

  it('treats status 0 as a network error', () => {
    const e = normalizeError(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }));
    expect(e.isNetwork).toBeTrue();
    expect(e.userMessage).toContain('Cannot reach the server');
  });

  it('falls back to status based messages when the body is not a problem document', () => {
    expect(normalizeError(new HttpErrorResponse({ status: 503, error: 'Service Unavailable' })).userMessage).toContain('temporarily unavailable');
    expect(normalizeError(new HttpErrorResponse({ status: 404, error: null })).userMessage).toContain('could not find');
    expect(normalizeError(new HttpErrorResponse({ status: 500, error: '<html>stack trace</html>' })).userMessage).not.toContain('stack');
  });

  it('reads Retry-After and correlation id headers on 429', () => {
    const e = normalizeError(
      new HttpErrorResponse({
        status: 429,
        headers: new HttpHeaders({ 'Retry-After': '7', 'X-Correlation-Id': 'hdr-1' }),
        error: { code: 'RATE_LIMITED' },
      }),
    );
    expect(e.retryAfterSeconds).toBe(7);
    expect(e.correlationId).toBe('hdr-1');
  });

  it('is idempotent and wraps unknown errors', () => {
    const first = normalizeError(new HttpErrorResponse({ status: 500 }));
    expect(normalizeError(first)).toBe(first);
    expect(normalizeError(new Error('boom')).code).toBe('CLIENT_ERROR');
  });
});

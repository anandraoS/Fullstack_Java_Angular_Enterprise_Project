import { asApiError, genericSignInError, isApiError, isNetworkError, normalizeFailure, normalizeUnknown } from './apiError';
import { DEFAULT_CONFIG, apiBaseUrl, getConfig, loadConfig, setConfig } from './config';
import { fmtDate, humanize, money, shortId } from './format';

describe('normalizeFailure (problem+json -> ApiError)', () => {
  it('uses the friendly text for the problem code and keeps diagnostics out of the headline', () => {
    const e = normalizeFailure({
      status: 409,
      body: { code: 'INVALID_STATE_TRANSITION', title: 'Conflict', detail: 'SQL: secret table name', correlationId: 'c-1', errors: [{ field: 'x', message: 'bad' }] },
    });
    expect(e.code).toBe('INVALID_STATE_TRANSITION');
    expect(e.userMessage).toBe('That action is not possible in the current state of the order.');
    expect(e.userMessage).not.toContain('SQL');
    expect(e.detail).toBe('SQL: secret table name'); // kept for diagnostics only
    expect(e.correlationId).toBe('c-1');
    expect(e.fieldErrors).toEqual([{ field: 'x', message: 'bad' }]);
  });

  it.each([
    [0, 'Cannot reach the server'],
    [400, 'not valid'],
    [401, 'session has expired'],
    [403, 'permission'],
    [404, 'could not find'],
    [409, 'changed by someone else'],
    [413, 'too large'],
    [422, 'not valid'],
    [429, 'Too many requests'],
    [502, 'temporarily unavailable'],
    [503, 'temporarily unavailable'],
    [504, 'temporarily unavailable'],
    [500, 'Something went wrong'],
  ])('falls back to a status based message for HTTP %i without a problem body', (status, text) => {
    const e = normalizeFailure({ status, body: '<html>proxy error</html>' });
    expect(e.userMessage).toContain(text);
    expect(e.code).toBe(status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`);
  });

  it('parses Retry-After seconds only and falls back to the correlation header', () => {
    expect(normalizeFailure({ status: 429, body: { code: 'RATE_LIMITED' }, retryAfter: '7' }).retryAfterSeconds).toBe(7);
    expect(normalizeFailure({ status: 429, body: null, retryAfter: 'Wed, 21 Oct' }).retryAfterSeconds).toBeNull();
    expect(normalizeFailure({ status: 503, body: null, correlationHeader: 'hdr-1' }).correlationId).toBe('hdr-1');
  });

  it('flags network errors and ignores malformed fields', () => {
    const net = normalizeFailure({ status: 0, body: undefined });
    expect(isNetworkError(net)).toBe(true);
    const odd = normalizeFailure({ status: 400, body: { code: 5, title: 3, detail: {}, errors: 'x' }, statusText: 'Bad Request' });
    expect(odd.code).toBe('HTTP_400');
    expect(odd.title).toBe('Bad Request');
    expect(odd.detail).toBeNull();
    expect(odd.fieldErrors).toEqual([]);
  });
});

describe('other error helpers', () => {
  it('normalizeUnknown wraps client errors, passes ApiErrors through', () => {
    const c = normalizeUnknown(new Error('boom'));
    expect(c.status).toBe(-1);
    expect(c.code).toBe('CLIENT_ERROR');
    expect(c.detail).toBe('boom');
    expect(normalizeUnknown('str').detail).toBe('Unexpected error');
    expect(normalizeUnknown(c)).toBe(c);
    expect(isApiError(c)).toBe(true);
    expect(isApiError({})).toBe(false);
  });

  it('asApiError accepts RTK serialized errors and nothing', () => {
    expect(asApiError(null)).toBeNull();
    expect(asApiError(undefined)).toBeNull();
    expect(asApiError({ message: 'x' })?.code).toBe('CLIENT_ERROR');
  });

  it('genericSignInError hides detail for 401/403 only', () => {
    const base = normalizeFailure({ status: 401, body: { code: 'UNAUTHENTICATED', detail: 'unknown user bob' } });
    const g = genericSignInError(base);
    expect(g.userMessage).toBe('Sign-in failed. Check your username and password.');
    expect(g.detail).toBeNull();
    const down = normalizeFailure({ status: 503, body: null });
    expect(genericSignInError(down)).toBe(down);
  });
});

describe('runtime config', () => {
  afterEach(() => setConfig({ apiBaseUrl: 'http://oms.test/api/v1', pollIntervalMs: 20, pollMaxIntervalMs: 60 }));

  it('merges onto defaults and strips trailing slashes from apiBaseUrl', () => {
    setConfig({ apiBaseUrl: '/gw/api/v1///' });
    expect(apiBaseUrl()).toBe('/gw/api/v1');
    expect(getConfig().pollIntervalMs).toBe(DEFAULT_CONFIG.pollIntervalMs);
  });

  it('loadConfig reads config.json, and keeps defaults on HTTP or network failure', async () => {
    await loadConfig((async () => new Response(JSON.stringify({ apiBaseUrl: '/x/api/v1', pollIntervalMs: 5 }), { status: 200 })) as typeof fetch);
    expect(getConfig()).toMatchObject({ apiBaseUrl: '/x/api/v1', pollIntervalMs: 5, pollMaxIntervalMs: DEFAULT_CONFIG.pollMaxIntervalMs });
    await loadConfig((async () => new Response('nope', { status: 404 })) as typeof fetch);
    expect(getConfig().apiBaseUrl).toBe('/x/api/v1'); // unchanged
    setConfig({});
    await loadConfig((async () => Promise.reject(new Error('offline'))) as typeof fetch);
    expect(getConfig()).toEqual(DEFAULT_CONFIG);
  });
});

describe('format helpers', () => {
  it('money tolerates null prices (REJECTED orders, defect D-03)', () => {
    expect(money(2.5)).toBe('2.50');
    expect(money(null, '-')).toBe('-');
    expect(money(undefined)).toBe('');
    expect(money('7')).toBe('7');
  });

  it('fmtDate handles empty and invalid input and formats in several styles', () => {
    expect(fmtDate(null)).toBe('');
    expect(fmtDate('not a date')).toBe('');
    const d = new Date(2026, 9, 5, 7, 8, 9).toISOString();
    expect(fmtDate(d, 'time')).toBe('07:08:09');
    expect(fmtDate(d, 'short')).toBe('Oct 5, 07:08');
    expect(fmtDate(d)).toBe('Oct 5, 2026, 07:08:09');
  });

  it('shortId and humanize', () => {
    expect(shortId('12345678-aaaa')).toBe('12345678');
    expect(shortId(null)).toBe('');
    expect(humanize('COMPENSATION_PENDING')).toBe('COMPENSATION PENDING');
  });
});

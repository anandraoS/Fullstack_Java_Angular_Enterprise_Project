import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { setConfig } from '../core/config';
import { setRetryBaseDelay } from '../store/baseQuery';
import { server, TEST_API } from './server';

// jsdom has no matchMedia: report a wide (desktop) viewport so the permanent navigation drawer renders.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

// jsdom ships its own AbortController/AbortSignal, which Node's undici Request rejects ("Expected signal to be an
// instance of AbortSignal"). Tests never abort, so the signal is dropped before the Request is built.
const NodeRequest = globalThis.Request;
class SignalSafeRequest extends NodeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(input, init ? { ...init, signal: undefined } : init);
  }
}
globalThis.Request = SignalSafeRequest as typeof Request;

// Node's fetch needs absolute URLs; MSW intercepts this origin.
setConfig({ apiBaseUrl: TEST_API, pollIntervalMs: 20, pollMaxIntervalMs: 60 });
setRetryBaseDelay(0);

// lazy route chunks + MUI make first renders slow on busy machines
configure({ asyncUtilTimeout: 6000 });

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  sessionStorage.clear();
});
afterAll(() => server.close());

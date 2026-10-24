# React UI architecture (`frontend/oms-ui-react`)

Second front end for the OMS, feature-equivalent to the Angular UI (see [angular-architecture.md](angular-architecture.md) and [ADR-0013](../adr/0013-dual-frontend-angular-and-react.md)). It uses only the gateway contract in [00-shared-contract.md](00-shared-contract.md); nothing in the backend knows which UI is calling.

## Layers

```
main.tsx            loadConfig(config.json) -> createAppStore() -> <App/>
App.tsx             Provider (Redux) > ThemeProvider (MUI) > BrowserRouter > routes + ToastHost + session-expiry timer
routes.tsx          React.lazy() per screen; guards as layout routes (RequireAuth, RequireRole, GuestOnly)
layout/AppShell     skip link, app bar (cart, account menu), role-aware nav (<nav aria-label="Main navigation">), ErrorBoundary + Suspense
store/              Redux Toolkit
  authSlice         session (memory), logoutReason, selectors; pure helpers (sessionFromToken, hasAnyRole, parseStoredSession)
  cartSlice         cart lines + pure helpers shared in spirit with the Angular CartStore
  uiSlice           toast queue, mobile drawer
  baseQuery         the "interceptor chain": headers, GET-only retry, problem+json normalization, 401 logout, 429 toast
  api               RTK Query endpoints (all server state), tags and invalidation
  index             store factory, sessionStorage mirror, cache reset on logout
hooks/              useListQuery (page/size/sort/filters), useDebouncedValue, useOrderPolling, useSessionExpiry
components/         PageHeader, DataTable (+pagination), StatusChip, StatusTimeline, EmptyState, ErrorBanner, LoadingOverlay,
                    ConfirmDialog (+useConfirm), ToastHost, ErrorBoundary, guards
features/           login, dashboard, catalog, inventory (+StockAdjustDialog), orders (list/create/detail), ops (dashboard, failed orders, audit),
                    notifications, fulfillment, misc (403/404)
```

## Request flow

```
component --useXQuery/useXMutation--> RTK Query --> omsBaseQuery
                                                     1. fetchBaseQuery (URL = runtime apiBaseUrl + path)
                                                        prepareHeaders: X-Correlation-Id (UUID), Authorization: Bearer (never on /auth/token)
                                                     2. GET/HEAD + (network error | 502 | 503 | 504): retry 2x with 200/400 ms backoff
                                                     3. any failure -> ApiError { status, code, userMessage, correlationId, fieldErrors, retryAfterSeconds }
                                                     4. 401 (not the login call, signed in) -> dispatch(loggedOut('expired')) -> RequireAuth redirects,
                                                        listener middleware resets the whole RTK Query cache and the cart slice clears
                                                        429 -> dispatch(toast.warn("... Try again in Ns."))
```

`ApiError` is a plain serializable object (not an `Error` subclass) so it can live in the Redux store and the RTK Query cache. The user-facing message always comes from a code-to-text table; the server `detail` is kept for diagnostics only and the sign-in screen replaces 401/403 text with one generic sentence.

## Server state vs client state

| Kind | Where | Why |
|---|---|---|
| Orders, products, stock, reservations, fulfillments, notifications, audit, ops, health | RTK Query cache | fetched, cached 30 s after last use, refetched on mount/arg change, invalidated by mutations |
| Session (token, roles, customerId), cart, toasts | slices | cross-screen client state; session mirrored to `sessionStorage` |
| Filters, page, size, sort of one screen | `useListQuery` (reducer in the component) | local to the screen, serialised into the query args; changing a filter resets to page 0 |
| Form values | React Hook Form | per form, validated by Zod schemas |
| Idempotency key | `IdempotencyKeyHolder` in component state | one logical submission; see below |

## Order creation and idempotency

`IdempotencyKeyHolder` (same rules as Angular): a new key for each new logical submission; the same key when the previous attempt had an unknown outcome (network error, status 0, or 5xx) and the request body fingerprint is unchanged; a new key after a definite answer (2xx/4xx) or when the body changed (same key plus a different body would be 422 `IDEMPOTENCY_KEY_REUSED`). The submit button is disabled while the mutation is in flight. 400 field errors (`items[0].quantity`, `shippingAddress.city`) are mapped to controls via `setError`; unknown fields stay in the banner. Because `POST /orders` is never retried by the base query, retry is always a user decision with the key preserved.

## Order detail polling

`useOrderPolling` calls one RTK Query endpoint (`getOrderSnapshot`: order + history + saga for staff, fetched in parallel; a failing saga call only blanks the saga section). `pollingInterval` is recomputed after every completed fetch with exponential backoff (1.5 s x 1.5^n, capped at 8 s, from `config.json`) and becomes 0 at a terminal status, on error, after "Stop auto-refresh", and on unmount (RTK Query releases the subscription and its timer). The hook adjusts its small state during render (the documented pattern for derived state) instead of in effects.

## Security

* Route guards and role-aware navigation are UX only; the backend authorizes every call (other customers' orders return 404).
* Token: Redux memory + `sessionStorage` (survives reload, per tab, readable by script on the origin: XSS exposure). Production should use authorization code + PKCE with a BFF and HttpOnly cookies. The login form has no prefilled password; the password is never stored.
* `returnUrl` accepts only same-app relative paths (no `//`, no scheme, no backslash).
* No CDN fonts or icons (system font stack; no icon package). nginx CSP: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'` (Emotion injects styles at runtime), `connect-src 'self'`, `frame-ancestors 'none'`. The React build emits no inline scripts.
* The UI renders server strings only as React text nodes (escaped); there is no `dangerouslySetInnerHTML`.
* On logout every cached response and the cart are discarded, so the next user of the tab sees nothing of the previous session.

## Accessibility

Skip link; the page `h1` is focusable and takes focus when a screen mounts (also sets `document.title`); labelled controls (MUI `TextField` labels) with error text; `role="alert"` for inline errors, `role="status"` for toasts (never competing with alerts), loading and status changes; dialogs trap focus, close on Escape and restore focus (`alertdialog` for confirmations, initial focus on the reason field or on the safe button); sortable headers are buttons with `aria-sort`; scroll regions are focusable; status is text plus glyph, never colour only; the add/remove item rows move focus to a stable control; `prefers-reduced-motion` respected. `eslint-plugin-jsx-a11y` runs in CI. No automated axe/Lighthouse pass has been run (see README limitations).

## Testing

| Layer | Tool | What |
|---|---|---|
| Pure logic | Vitest | slices, selectors, error normalization, config, timeline, Zod schemas, idempotency holder, list reducers, backoff |
| API layer | Vitest + MSW | headers, retries (GET only), 401/429 side effects, serializable errors, invalidation, persistence |
| Screens | Testing Library + MSW | the whole app is rendered (routes, guards, shell, toasts) at a URL with a seeded store: forms, dialogs, polling, null prices, role-aware navigation, pagination/filter/sort |
| End to end | Playwright (`tests/e2e`, `E2E_UI=react`) | the same 18 lifecycle specs as Angular, against the mock backend and the live stack |

## Angular to React mapping

See the table in `frontend/oms-ui-react/README.md` (signals/RxJS/Angular Material on one side, Redux Toolkit/RTK Query/MUI on the other).

# OMS UI (Angular 20)

Angular front end for the synthetic Enterprise Order Management and Fulfillment Platform. It talks only to the API gateway
(`/api/v1`, contract in `docs/architecture/00-shared-contract.md`). Standalone components, lazy-loaded routes, Angular
Material, strict TypeScript, Signals + RxJS, Reactive Forms. A feature-equivalent React + Redux UI lives next to it in
`../oms-ui-react` (ADR-0013); this app does not depend on it.

## Run

```
npm install
npm start                      # ng serve on http://localhost:4200, proxies /api -> http://localhost:18080 (proxy.conf.json)
npm test                       # Karma + Chrome headless (set CHROME_BIN to Chrome/Chromium/Edge)
npm run test:coverage
npm run lint
npx ng build --configuration production
```

Windows example: `CHROME_BIN="C:\Program Files\Google\Chrome\Application\chrome.exe" npm test`.
Start the backend with `scripts/run-local.sh` (it prints the demo password). Sign in as `customer1`, `customer2`,
`operator1` or `admin1`; the login form has no prefilled password.

## Configuration (no `.env`)

`public/config.json` is fetched before the app starts: `{ "apiBaseUrl": "/api/v1", "pollIntervalMs": 1500, "pollMaxIntervalMs": 8000 }`.
Replace the file (or, in the Docker image, set `OMS_API_BASE_URL`) to repoint a build without rebuilding. In the Docker
image nginx also proxies `/api/` to `API_GATEWAY_URL` (default `http://api-gateway:18080`), serves an SPA fallback,
sends CSP and other security headers, and runs as uid 101 on port 8080. The Dockerfile has not been built on this
machine (no Docker).

## Structure

```
src/app
  core/         models (contract types), config, auth (signal store + guards), http (interceptors, ApiError),
                api (typed services), state (ListStore, polling, cart)
  shared/       page-header, data-table (+paginator), status-chip, status-timeline, empty-state, confirm-dialog,
                error-banner, loading-overlay
  features/     login, dashboard, catalog, inventory, orders (list/create/detail), ops (dashboard, failed orders, audit),
                notifications, fulfillment, misc (403/404)
```

## HTTP layer

Interceptor order (`app.config.ts`): `correlation -> auth -> retry -> error`.

* correlation: `X-Correlation-Id` (UUID) on every API call; the id shown in error banners as "Reference" lets support
  find the request in logs.
* auth: `Authorization: Bearer` on API calls only (never on `/auth/token`, never on foreign origins).
* retry: only idempotent `GET`/`HEAD`, only for network errors, 502, 503, 504, two retries with 200ms/400ms backoff.
  Writes are never retried automatically.
* error: every failure becomes an `ApiError` built from the RFC 7807 body (`code` -> friendly text, field errors,
  correlation id, `Retry-After`). Raw server text is never the headline message. `401` (outside login) logs out and
  redirects to `/login?reason=expired`; `429` shows a toast with the wait time.

## Order creation and idempotency

`POST /orders` requires an `Idempotency-Key`. `IdempotencyKeyHolder` picks it per submit attempt:

* new logical submission -> new key;
* previous attempt had an unknown outcome (network error / 5xx) and the form is unchanged -> the same key is reused, so
  a retry can never create a second order (the server replays the original response with `Idempotent-Replay: true`);
* body changed or a definite answer was received -> new key (same key + different body would be a 422).

The submit button is also locked while a request is in flight. Server field errors (`items[0].quantity`,
`shippingAddress.city`) are mapped back onto the controls.

## Status polling

The order detail page polls order + history (+ saga for staff) with `pollUntil`: RxJS `expand` + `timer` with backoff
(`1.5s * 1.5^n`, capped at 8s), stopping on a terminal status, `takeUntil` on destroy or "Stop auto-refresh", or an error.

## Security notes

* Route guards and role-aware navigation are **UX only**. The backend authorizes every request (roles, ownership, 404 for
  other customers' orders); a tampered client gains nothing.
* Token storage: the access token is held in a signal and mirrored to `sessionStorage` so reloads keep the session.
  sessionStorage is per tab and cleared on close, but is readable by any script on the origin (XSS). Production should
  use authorization-code + PKCE with a BFF and HttpOnly cookies. The nginx CSP (`script-src 'self'`) reduces, not removes, XSS risk.
* `returnUrl` after login only accepts same-app relative paths (no open redirect). The password is never stored.
* No external fonts/CDNs (CSP `default-src 'self'`); no icon font is used.

## Accessibility

Skip link; focus moves to the page `h1` after every navigation; labelled form controls with `mat-error` messages;
`role="alert"` for errors, `role="status"`/`aria-live` for loading and status changes; dialogs trap and restore focus
(`autoFocus`, `restoreFocus`, `alertdialog` for confirmations); sortable headers are buttons with `aria-sort`; status
is never colour-only (text + glyph); tables have captions/labels and scroll regions are keyboard focusable;
`prefers-reduced-motion` respected; text colours chosen for >= 4.5:1 contrast.

## React/Redux mental model -> Angular

| React / Redux | This app |
|---|---|
| store / slice | signal-based store service (`AuthStore`, `CartStore`, `ListStore`, `OrderDetailStore`, `DashboardStore`): private `signal`s, public `asReadonly()` |
| reducer (`(state, action) => state`) | pure update functions exported next to the store (`addToCart`, `withFilters`, `listLoaded`, `buildTimeline`); unit-tested without TestBed |
| action / dispatch | a store method call (`store.setFilters(...)`, `cart.add(...)`) |
| selector (`createSelector`) | `computed()` (`items`, `total`, `isStaff`, `cancellable`) |
| thunk / saga / RTK listener (effects) | RxJS pipelines inside the store: `switchMap` request pipeline in `ListStore`, `expand`+`timer` polling in `pollUntil` |
| `useEffect` | `effect()` (order detail restarts polling when the route `id` changes; `untracked` for non-dependencies); `toSignal` / `takeUntilDestroyed` for observable subscriptions (cleanup is automatic) |
| `useState` | local `signal()` in a component |
| Context / Provider | dependency injection: `providedIn: 'root'` services, component-level `providers: [OrderDetailStore]` for scoped state |
| props / children | `input()` / `output()` / content projection (`<ng-content>`, `ng-template` for table actions) |
| react-router loaders / guards | route `canActivate` functional guards, `withComponentInputBinding()` binds `:id` to an `input()` |
| axios interceptors | `HttpInterceptorFn` chain |
| controlled forms / Formik | Reactive Forms (`FormBuilder`, `FormArray`, custom validators) |

## Tests

148 Karma specs (about 93% statement coverage): API services and interceptors (`HttpTestingController`), auth store and
guards, `ListStore` (including stale-response protection), polling with fake timers, cart, form validators and idempotency
key logic, the order form component (validation, item rows, key reuse), timeline, shared components, dialogs, and the
feature screens. Playwright end-to-end specs live in `tests/e2e` and run against a Node mock backend by default:

```
npx ng build                                    # UI bundle served by the mock
cd ../../tests/e2e && npm install && npx playwright test      # starts mock-backend/server.js on :4300, uses system Chrome

# against the live stack (scripts/run-local.sh + npm start):
E2E_BASE_URL=http://localhost:4200 E2E_DEMO_PASSWORD=<printed password> npx playwright test
# optionally E2E_API_URL=http://localhost:18080 to call the gateway directly from the API-level checks
```

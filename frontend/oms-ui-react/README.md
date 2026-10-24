# OMS UI (React + Redux Toolkit)

Second front end for the synthetic Enterprise Order Management and Fulfillment Platform, feature-equivalent to the Angular app in
`../oms-ui` and compatible with the same gateway contract (`docs/architecture/00-shared-contract.md`). The Angular app is not
touched by this folder; why there are two UIs: `docs/adr/0013-dual-frontend-angular-and-react.md`. Design details:
`docs/architecture/react-architecture.md`.

Stack: React 19, TypeScript (strict), Vite 7, Redux Toolkit (RTK Query + slices), React Router 7 (lazy routes, role guards),
React Hook Form + Zod, MUI 7 (Material UI; no CDN fonts, no icon package), Vitest + Testing Library + MSW, ESLint (typescript-eslint,
react-hooks, jsx-a11y). All dependencies are pinned to exact versions; `npm audit` (including dev dependencies) reports 0 vulnerabilities.

## Run

```
npm install
npm start               # Vite on http://localhost:4201, proxies /api -> http://localhost:18080 (override: OMS_GATEWAY_URL)
npm run build           # tsc -b (strict) + vite build -> dist/
npm run lint            # ESLint, zero errors
npm test                # Vitest (jsdom + MSW)
npm run test:coverage   # + v8 coverage (text summary, html, json-summary in coverage/)
```

Start the backend with `bash scripts/run-local.sh --no-build --json-logs` (prints a random demo password; users `customer1`, `customer2`,
`operator1`, `admin1`). `--ui react` also serves the built React UI on :4201 with a same-origin `/api` proxy (`--ui both` serves both UIs);
`OMS_GATEWAY_PORT=<port>` moves the gateway when another project holds 18080. There is no prefilled password anywhere.

## Configuration (no `.env`)

`public/config.json` is fetched before the first render: `{ "apiBaseUrl": "/api/v1", "pollIntervalMs": 1500, "pollMaxIntervalMs": 8000 }`.
Replace it, or in the Docker image set `OMS_API_BASE_URL`, to repoint a build without rebuilding. The image (same pattern as the Angular one)
is nginx-unprivileged on 8080 as uid 101, sends CSP and security headers, falls back to `index.html` for client routes and proxies `/api/`
to `API_GATEWAY_URL` (default `http://api-gateway:18080`). The Dockerfile has NOT been built (no Docker on this machine).
Compose: `docker compose --profile react ... up` exposes it on `127.0.0.1:4201`. Kubernetes: optional `base/frontend-react.yaml`
(`oms-ui-react` Deployment + Service, not routed by the Ingress; `kubectl port-forward svc/oms-ui-react 4201:8080`).

## Structure

```
src/
  models.ts         contract types (verbatim copy of the Angular models)
  core/             apiError (problem+json normalization), config (runtime config), format
  store/            authSlice, cartSlice, uiSlice, baseQuery (headers/retry/errors/401/429), api (RTK Query), index (store factory)
  hooks/            useListQuery, useDebouncedValue, useOrderPolling (polling.ts), useSessionExpiry, typed redux hooks
  components/       PageHeader, DataTable, StatusChip, StatusTimeline, EmptyState/ErrorBanner/LoadingOverlay, ConfirmDialog (+useConfirm),
                    ToastHost, ErrorBoundary, guards
  layout/           AppShell (skip link, app bar, role-aware nav, drawer)
  features/         login, dashboard, catalog, inventory, orders, ops, notifications, fulfillment, misc
  test/             MSW server, fixtures, render helpers, setup
```

Screens: Login, Dashboard, Catalog (search/category/in-stock, add to cart), Inventory (stock + reservations, adjust dialog, low-stock filter),
Create order (customer fixed for CUSTOMER, select for staff, dynamic item rows, idempotency key), Order list (status/date/search filters, sort, paging),
Order detail (items, status timeline, history, saga for staff, cancel dialog, polling with backoff), Operations (health + messaging per service),
Failed-order investigation (+ retry, reconciliation for ADMIN), Audit, Notifications, Fulfillment, 403 and 404.

## State management: why RTK Query for server state and slices for the rest

* **Server state** (orders, products, stock, ops...) is a cache problem: fetch, de-duplicate, invalidate after writes, poll, drop on logout. RTK Query
  does that declaratively (`providesTags`/`invalidatesTags`, `pollingInterval`, `refetchOnMountOrArgChange`, one `omsBaseQuery`). Hand-written
  thunks plus slices would re-implement it (loading flags, stale-response guards, cache keys) for no gain.
* **Client state** is small and cross-screen: `auth` (session in memory, mirrored to `sessionStorage`), `cart`, `ui` (toasts, drawer). These are plain
  slices with pure helper functions and `createSelector` selectors, unit-tested without React.
* **Screen-local state** (filters, page, sort, form values, dialog open) stays in components (`useListQuery`, React Hook Form, `useState`); it is
  serialised into RTK Query arguments, so a late response can never overwrite a newer query.
* On `loggedOut` a listener middleware calls `omsApi.util.resetApiState()` and the cart slice clears: no data of the previous user survives.

## React/Redux/RTK Query vs Angular/RxJS/Signals (this repo)

| Concern | React app (here) | Angular app (`../oms-ui`) |
|---|---|---|
| Global client state | Redux Toolkit slices (`authSlice`, `cartSlice`, `uiSlice`) | signal stores (`AuthStore`, `CartStore`) in `providedIn: 'root'` services |
| Reducer | slice reducers (Immer) plus exported pure helpers (`addToCart`, `hasAnyRole`) | pure update functions next to the stores (`addToCart`, `withFilters`) |
| Action / dispatch | `dispatch(cartAdd(...))` | `cart.add(...)` method call |
| Selector | `createSelector` (`selectIsStaff`, `selectCartCount`) | `computed()` |
| Server state, caching | RTK Query (`createApi`, cache keyed by endpoint+args, tags) | hand-rolled `ListStore` / `OrderDetailStore` with RxJS `switchMap` |
| Async side effect | RTK Query lifecycle, listener middleware, `async` handlers | RxJS pipelines (`expand`, `timer`, `forkJoin`, `catchError`) |
| Polling with backoff | `pollingInterval` recomputed per completed fetch (`useOrderPolling`) | `pollUntil` (`expand` + `timer`) with `takeUntil` |
| Stale-response protection | argument-keyed cache; only the latest args render | `switchMap` |
| HTTP interceptors | `omsBaseQuery` wrapper (headers, GET retry, error normalization, 401, 429) | `HttpInterceptorFn` chain `correlation -> auth -> retry -> error` |
| Errors | serializable `ApiError` object | `ApiError` class |
| `useState` / `useEffect` | `useState` / `useEffect` (state derived during render where possible) | `signal()` / `effect()` |
| Dependency injection | module imports + Redux `Provider`; `createAppStore()` factory for tests | Angular DI, component `providers` |
| Routing, guards | React Router layout routes (`RequireAuth`, `RequireRole`, `GuestOnly`), `React.lazy` | `canActivate` functional guards, `loadComponent` |
| Forms | React Hook Form + Zod (`useFieldArray` for item rows) | Reactive Forms (`FormArray`, custom validators) |
| UI kit | MUI | Angular Material |
| Dialogs | MUI `Dialog` (`alertdialog` via paper role), `useConfirm()` promise hook | `MatDialog` + `afterClosed()` |
| Toasts | `uiSlice` queue + `Snackbar` (`role="status"`) | `MatSnackBar` |
| Error containment | `ErrorBoundary` per route | global `ErrorHandler` |
| Unit tests | Vitest + Testing Library + MSW | Karma + Jasmine + `HttpTestingController` |

## Behaviours (identical to Angular, all covered by tests)

* **Idempotency**: every `POST /orders` carries `Idempotency-Key`. The key is reused for a retry only after an unknown outcome (network error or 5xx)
  with an unchanged body, and is renewed for any new submission, changed body, or definite answer. Submit is locked while in flight.
* **Errors**: RFC 7807 bodies become an `ApiError`; users see friendly text by `code`, field errors on controls, and the correlation id as "Reference".
  Raw `detail` is never the message. Sign-in failures show one generic sentence.
* **401** outside the login call logs out and returns to `/login?reason=expired`; **429** raises a toast with the `Retry-After` wait.
* **Retry**: only GET/HEAD, only network/502/503/504, twice (200 ms, 400 ms). Writes are never retried automatically.
* **REJECTED orders** have `null` sku/name/prices (defect D-03): items render `-` instead of crashing.
* **Role-aware navigation** and route guards are UX only; the backend authorizes everything.

## Security notes

* Token in Redux memory and `sessionStorage` (survives reload; per tab; readable by script on the origin). The trade-off is documented in
  `authSlice.ts`; production should use authorization code + PKCE with a BFF and HttpOnly cookies.
* `returnUrl` only accepts same-app relative paths. The password is never stored. Bearer token is never sent to `/auth/token`.
* No `dangerouslySetInnerHTML`, no CDN assets. CSP: `script-src 'self'`; `style-src 'unsafe-inline'` is needed because MUI/Emotion injects styles
  at runtime (a nonce-based Emotion cache would remove it; not done).
* Logout clears the API cache and the cart.

## Accessibility notes

Skip link; focus moves to the page `h1` when a screen mounts; labelled form controls with visible error text; `role="alert"` for inline errors and
`role="status"` for toasts, loading and order-status announcements; dialogs trap focus, close on Escape and restore focus; sortable headers are buttons
with `aria-sort`; scrollable table regions are keyboard focusable; status is text plus glyph; item rows move focus predictably; reduced motion respected;
`jsx-a11y` lint rules. Not done: an automated axe/Lighthouse run and a manual screen-reader pass.

## Tests

Unit and component: `npm test` runs 207 Vitest tests in 9 files (slices and selectors, error normalization, API layer with MSW incl. GET-only retry and
401/429 handling, guards and role-aware navigation, login, Zod schemas, order form with item rows, idempotency key reuse and renewal, server field errors,
timeline, order detail with null prices, cancel flow, polling, pagination/filter/sort, every screen). Coverage (v8, `npm run test:coverage`, run on
this machine): **96.3 % statements, 93.5 % branches, 94.5 % functions, 96.7 % lines**.
Three harmless "not wrapped in act(...)" notices from async toasts/polling remain in the test output.

End to end (the existing Playwright specs, unchanged, parameterized by `E2E_UI`):

```
npm run build
cd ../../tests/e2e && npm install
E2E_UI=react npx playwright test                                   # mock backend serves frontend/oms-ui-react/dist on :4300
# live: bash scripts/run-local.sh --no-build --json-logs --ui react  (prints the password; add OMS_GATEWAY_PORT if 18080 is taken)
E2E_UI=react E2E_BASE_URL=http://localhost:4201 E2E_DEMO_PASSWORD=<printed password> npx playwright test
```

## Limitations (honest)

* The Docker image, the compose profile and the Kubernetes manifest were not built or applied (no Docker or cluster); kustomize/kubeconform pass.
* No automated or screen-reader accessibility audit beyond lint rules and role-based Playwright locators; contrast was chosen, not measured.
* The access token is readable by script on the origin (see Security notes).
* MUI renders the account menu, drawer and select popups in portals; only desktop Chrome was exercised (the narrow-screen drawer is unit-tested by
  logic, not visually verified).
* Money is rendered from the JSON number (scale 2) with `toFixed(2)`; there is no currency conversion or locale formatting.
* Date/time uses the browser's local time zone.
* The type definitions are copied from the contract, not generated from `specs/openapi/*.json`; drift is caught only by tests and e2e.
* The Ops screen polls the four messaging endpoints and the health endpoint every 10 s while open; there is no websocket/SSE push.

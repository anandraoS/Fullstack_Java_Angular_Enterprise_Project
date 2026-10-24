# Angular architecture (`frontend/oms-ui`)

Decision records: [ADR-0012 state management](../adr/0012-angular-state-management.md), [ADR-0010 security](../adr/0010-jwt-oidc-security-model.md). Verified against `frontend/oms-ui/src/app` on disk. Angular 20, standalone components only (no NgModules), Angular Material.

## 1. Layers and folders

| Layer | Folder | Responsibility |
|---|---|---|
| Bootstrap | `app.config.ts`, `main.ts`, `app.routes.ts` | providers, interceptor order, `provideAppInitializer` that loads `config.json`, lazy routes |
| Core (singletons) | `core/` | `config.service.ts`, `auth/` (`auth.store.ts`, `guards.ts`), `http/` (`interceptors.ts`, `api-error.ts`), `api/apis.ts` (typed API clients), `models.ts` (types mirroring the contract), `notify/toaster.ts`, `state/` (`list-store.ts`, `cart.store.ts`, `polling.ts`) |
| Features | `features/` | one folder per screen: catalog, dashboard, fulfillment, inventory, login, notifications, orders (list, create, detail, form, detail store), ops (ops dashboard, failed orders, audit), misc |
| Shared UI | `shared/components/` | presentational components: `data-table`, `page-header`, `status-chip`, `status-timeline`, `timeline`, `confirm-dialog`, `feedback` |
| Testing | `testing/test-utils.ts` and `*.spec.ts` beside code | Karma/Jasmine |

Dependency direction: features depend on core and shared; core never imports features; shared components take inputs and emit outputs and do not call APIs.

## 2. Standalone components and lazy loading

Every feature route uses `loadComponent: () => import(...)`, so each screen is its own chunk and the initial bundle contains only bootstrap, login and shared code. `withComponentInputBinding()` lets route params bind to component `input()`s. Route `title`s are set per route. `app.config.ts` uses `provideZoneChangeDetection({ eventCoalescing: true })`, so the app is still zone-based; signals work with it and make a future zoneless switch feasible.

## 3. State with signals

* `AuthStore`: token and identity as signals, mirrored to `sessionStorage`; `isAuthenticated`, roles and customer id derived; `logout('expired')` called by the error interceptor on 401.
* `CartStore`: a private writable `signal` of lines with `computed` `count` and `total`.
* `list-store.ts`: generic paginated-list state (`status`, `data`, `error`) with pure update functions (`listLoading`, `listLoaded`, `listFailed`, `withFilters`, `withPage`, `withSort`); changing filters or sort resets to page 0.
* `order-detail.store.ts`: per-screen store combining order, history and polling.
* Server data is refetched rather than mirrored in a global cache. After an admin retry the UI ignores the 2xx body and re-fetches (contract section 5.5).

## 4. HTTP pipeline (interceptors)

Order in `app.config.ts`: `correlationInterceptor`, `authInterceptor`, `retryInterceptor`, `errorInterceptor`. Requests flow left to right, errors right to left.

* `correlationInterceptor`: adds `X-Correlation-Id` (UUID) to API calls so a click can be traced through gateway and services (ADR-0011).
* `authInterceptor`: adds `Authorization: Bearer` only to API calls, never to the token endpoint or foreign origins.
* `retryInterceptor`: retries only GET/HEAD, twice, with 200 ms then 400 ms backoff, only for status 0, 502, 503, 504. Mutations are never retried here; order creation relies on `Idempotency-Key` so the user (or caller) can safely resubmit.
* `errorInterceptor`: normalizes everything into `ApiError` (reads RFC 7807 `code`, `correlationId`, `errors[]`), logs out on 401 (except on login), toasts 429 with `Retry-After`. The UI shows a generic message for login failure and never echoes server `detail`.

## 5. Guards are UX, not security

`authGuard`, `roleGuard` (reads route `data.roles`, for example staff = OPERATOR and ADMIN) and `guestGuard` only hide screens a user cannot use and avoid dead-end navigation. Anyone can edit client code or call the API directly; every endpoint re-checks roles and ownership in the backend (ADR-0010, contract section 4). Never put a secret or an authorization decision in the bundle. The cart and role-dependent menus are convenience only.

## 6. Polling

Order status is eventually consistent (saga), so the detail screen polls: `pollUntil(fetchFn, isDone, {initialMs, maxMs, factor 1.5}, stop$)` in `core/state/polling.ts` emits immediately, then re-polls with growing delay capped at `maxMs`, completes on a terminal status or when the component is destroyed (`stop$`), and ends on error so the screen can offer a manual refresh. Defaults come from `config.json`. There is no WebSocket or SSE; that is a possible later upgrade if polling load matters.

## 7. Runtime configuration

`public/config.json` holds `{ "apiBaseUrl": "/api/v1", "pollIntervalMs": 1500, "pollMaxIntervalMs": 8000 }`. `ConfigService.load()` runs in `provideAppInitializer` before the app renders, falling back to `DEFAULT_CONFIG`. The same build runs anywhere: in the nginx image (`frontend/oms-ui/nginx.conf`, `Dockerfile`) the file is generated from `OMS_API_BASE_URL` and `/api/` is proxied to `API_GATEWAY_URL`; in dev `proxy.conf.json` forwards `/api` to the gateway on 18080. Serving UI and API same-origin means no CORS configuration is needed.

## 8. Testing

Pure functions (list reducers, `backoffDelay`, error normalization) are tested without TestBed; stores, guards, interceptors and screens have `*.spec.ts` files next to them. Tests run with Karma (`karma.conf.js`); ESLint config in `eslint.config.js`. Browser-level end-to-end checks live in `tests/e2e`. Not verified here: behaviour on browsers other than the headless one used by Karma.

## 9. React/Redux to Angular mapping

A working React + Redux Toolkit app now sits beside this one (`frontend/oms-ui-react`, [react-architecture.md](react-architecture.md), ADR-0013); the concept table below is the quick reference, and the React app's README has the implementation-backed side-by-side table.

| React / Redux idea | Angular in this project |
|---|---|
| Function component | Standalone `@Component` class with template |
| Props | `input()` / `@Input`; route params through `withComponentInputBinding` |
| Callback props, events | `output()` |
| `useState` | `signal()` |
| `useMemo` / selector | `computed()` |
| `useEffect` | `effect()` for side effects on signals; RxJS subscriptions with `takeUntilDestroyed` for IO |
| Redux store slice | injectable store class (`AuthStore`, `CartStore`, list store) |
| Reducer | exported pure functions (`listLoaded`, `withFilters`) |
| Action / dispatch | method call on the store |
| Redux Thunk / Saga / RTK Query | RxJS operators (`switchMap`, `retry`, `expand` in `pollUntil`); no built-in query cache |
| Context provider | DI (`providedIn: 'root'` or component `providers`) |
| React Router loaders and guards | route `canActivate` guards, lazy `loadComponent` |
| Axios interceptors / middleware | `HttpInterceptorFn` chain |
| `React.lazy` + Suspense | `loadComponent` dynamic import |
| Error boundary | `provideBrowserGlobalErrorListeners` plus the error interceptor and toaster |
| `.env` build-time variables | runtime `config.json` loaded by `provideAppInitializer` |
| Redux DevTools | none by default (see ADR-0012 revisit triggers) |

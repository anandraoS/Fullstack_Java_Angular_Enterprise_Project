## React state-management design

The React app splits state by who owns the truth, which is the central design decision of ADR-0013.

| State | Owner | Mechanism | Rule |
|---|---|---|---|
| Server state: orders, products, stock, reservations, fulfillments, notifications, audit, ops, health | the backend | RTK Query cache (`createApi`), keyed by endpoint and arguments, tags for invalidation, polling for order detail | never copy server data into a slice; mutations invalidate tags; the cache is dropped on logout |
| Client state: session, cart, toasts, drawer | the browser | `createSlice` slices (`auth`, `cart`, `ui`), pure helper functions, `createSelector` selectors | serializable only; the session is mirrored to `sessionStorage` by middleware; reducers stay pure |
| Screen-local state: filters, page, sort, dialog open | the component | `useListQuery` (reducer), `useState`, React Hook Form with Zod | serialised into RTK Query arguments so a late response cannot overwrite a newer query |
| One logical submission | the order form | `IdempotencyKeyHolder` | same `Idempotency-Key` only after an unknown outcome with an unchanged body |

Two side effects deserve a sentence each. `omsBaseQuery` is the single place that attaches the correlation id and bearer token (never on `/auth/token`), retries only GET and HEAD on network errors and 502, 503 and 504, converts failures to a serializable `ApiError`, logs out on a 401 and toasts on a 429. A listener middleware reacts to `loggedOut` by resetting the whole RTK Query cache, and the cart slice clears itself on the same action, so the next user of a tab sees nothing of the previous session.

{{code:frontend/oms-ui-react/src/store/index.ts|const listener = createListenerMiddleware|8|ts}}

{{code:frontend/oms-ui-react/src/store/api.ts|createOrder: b.mutation|9|ts}}

## Angular and React side by side

| Concern | React app (`frontend/oms-ui-react`) | Angular app (`frontend/oms-ui`) |
|---|---|---|
| Global client state | Redux Toolkit slices `authSlice`, `cartSlice`, `uiSlice` | signal stores `AuthStore`, `CartStore` (`providedIn: 'root'`) |
| Derived state | `createSelector` | `computed()` |
| Server state and caching | RTK Query: cache, tags, polling | hand-rolled `ListStore` and `OrderDetailStore` with RxJS `switchMap`; no cache |
| Polling with backoff | `pollingInterval` recomputed per completed fetch (`useOrderPolling`) | `pollUntil` (`expand` and `timer`) with `takeUntil` |
| HTTP cross-cutting concerns | `omsBaseQuery` (headers, GET retry, errors, 401, 429) | interceptor chain correlation, auth, retry, error |
| Routing and guards | React Router layout routes `RequireAuth`, `RequireRole`, `GuestOnly`; `React.lazy` | `canActivate` guards; `loadComponent` |
| Forms | React Hook Form with Zod schemas | typed Reactive Forms with validator functions |
| Error containment | `ErrorBoundary` per route | global `ErrorHandler` |
| UI kit | MUI | Angular Material |
| Unit and component tests | Vitest, Testing Library, MSW | Karma, Jasmine, `HttpTestingController` |
| Change propagation | re-render, memoized selectors | signals, zone-based change detection (no `OnPush`) |
| Idempotent order submit | `IdempotencyKeyHolder` in `orderForm.ts` | `IdempotencyKeyHolder` in `order-form.ts` |

## Dual-UI testing matrix

The same `tests/e2e/specs/order-lifecycle.spec.ts` (18 serial tests) runs against both UIs in two modes. `E2E_UI` (angular by default, or react) selects the UI served by the mock backend and the live URL.

| UI | Mock backend (contract only) | Live stack (UI to gateway to real services on embedded Kafka and H2) |
|---|---|---|
| Angular | 18 / 18 | 18 / 18 |
| React | 18 / 18 | 18 / 18 |

All four configurations were executed on 2026-10-19; the live stack used the gateway on port 18085 (`OMS_GATEWAY_PORT`) because a foreign process held 18080. The unit layers differ per framework and are not shared:

| Layer | Angular | React |
|---|---|---|
| Unit and component | 149 of 149 (Karma and Jasmine) | 207 of 207 (Vitest), coverage 96.3 % statements, 93.5 % branches, 94.5 % functions, 96.7 % lines |
| Static checks | `ng build` | ESLint 0 errors, `tsc -b` strict, `vite build`, `npm audit` 0 vulnerabilities |
| Not executed | accessibility audit, browsers other than Chrome | Docker image, compose profile `react`, Kubernetes manifest, CI and Jenkins runs, axe and Lighthouse, screen reader, narrow-screen visual check |

{{code:tests/e2e/playwright.config.ts|^const ui|8|ts}}

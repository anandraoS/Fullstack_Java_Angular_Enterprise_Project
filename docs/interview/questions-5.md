# Interview questions Q151-Q164

Scope of this part of the question bank: fourteen questions on the **React + Redux Toolkit front end** (`frontend/oms-ui-react`), eight of them scenarios: server state versus client state, RTK Query caching and invalidation, hooks and effects, idempotent writes from the browser, polling, security (token storage, Content Security Policy), performance, testing, and keeping two front ends on one contract. The original brief mandated Angular; the user later explicitly asked for a React frontend as well (ADR-0013), so every answer points at real code. Evidence statements: 207 of 207 Vitest tests pass (coverage 96.3 percent statements, 93.5 percent branches), ESLint 0 errors, build OK, `npm audit` 0 vulnerabilities, and the same 18 Playwright specs pass 18/18 on four configurations (React mock, React live, Angular mock, Angular live), all run on 2026-10-19. Not executed: the React Docker image, the compose profile `react`, the Kubernetes manifest, CI runs, accessibility audits (axe, Lighthouse, screen reader) and a narrow-screen visual check.

### Q151. What is the difference between server state and client state, and how does the React app split them?
**Type:** Conceptual   **Level:** Beginner   **Topic:** Redux Toolkit, RTK Query, state design

**Short answer.** Server state is a copy of data that lives on the backend and can go stale (orders, products, stock); it is a caching problem. Client state exists only in the browser (the signed-in session, the cart, toasts). The React app keeps server state in RTK Query and client state in three small Redux Toolkit slices; screen-local state (filters, page, form values) stays in components.

**Detailed answer.** Kid-friendly picture: server state is a photo of the school timetable pinned on your wall, which may be out of date, so you need a rule for when to re-take the photo; client state is your own homework diary, which nobody else can change. In `frontend/oms-ui-react/src/store/api.ts` every gateway call is an RTK Query endpoint with cache tags; `authSlice.ts`, `cartSlice.ts` and `uiSlice.ts` hold the session, the cart lines and the toast queue. A list screen keeps its page, size, sort and filters in `useListQuery` (a reducer in the component) and passes them as query arguments, so the cache entry is keyed by those arguments. Rule of thumb: never copy server data into a slice, because then you own invalidation.

**Trade-offs and failure modes.**
* Copying fetched orders into a slice creates two sources of truth that drift.
* Putting filter state in the global store makes every screen share one filter by accident.
* The Angular app has no query cache; it refetches, which is simpler but chattier.

**Likely follow-ups.**
* Where does the session live? In the `auth` slice (memory), mirrored to `sessionStorage` by middleware in `store/index.ts`.
* Why is the cart not persisted? It is only a hand-off from the catalog to the order form; it is cleared on reload by design.

### Q152. How do RTK Query tags and invalidation keep screens consistent after a write?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** RTK Query caching

**Short answer.** Queries declare what they provide (`providesTags`), mutations declare what they invalidate (`invalidatesTags`); after a mutation succeeds, every active query holding an invalidated tag refetches. No screen calls `refetch` by hand.

**Detailed answer.** In `store/api.ts`, `listOrders` provides `Orders`, `getOrderSnapshot` provides `{ type: 'Order', id }`, and `cancelOrder` invalidates `{ type: 'Order', id }`, `Orders` and `Failed`. After a cancel, the open detail screen, any cached order list and the failed-orders list refresh, while stock and product caches are untouched. Cache entries are keyed by endpoint plus serialized arguments and are dropped 30 seconds after the last subscriber unmounts. `api.test.ts` has a test that a cancel causes the cached list to be refetched.

**Trade-offs and failure modes.**
* Too-coarse tags (one `Everything` tag) refetch too much; too-fine tags miss dependencies and leave stale screens.
* A mutation that changes data without invalidating anything is a stale-screen bug that unit tests rarely catch; the end-to-end specs do.
* Refetching is eventually consistent with the saga: an order may still be in an intermediate status when the refetch returns, which is why detail polls.

**Likely follow-ups.**
* Optimistic updates? Possible with `onQueryStarted` and `updateQueryData`; deliberately not used here because order status is decided by an asynchronous saga.
* Normalized cache? RTK Query does not normalize; entities live per query result.

### Q153. Scenario: After cancelling an order, the order list still shows it as CONFIRMED until you reload. How do you find and fix it?
**Type:** Scenario   **Level:** Intermediate   **Topic:** RTK Query invalidation, debugging

**Short answer.** Check whether the cancel mutation invalidates a tag that the list query provides. A stale list after a successful write means a missing or mismatched tag, or a list query that is not subscribed (unmounted) and so simply refetches on next mount.

**Detailed answer.** Reasoning steps: (1) reproduce with the Network tab: did the `POST /orders/{id}/cancel` return 200, and did a `GET /orders` follow? If there is no GET, the tags do not match. (2) Open `store/api.ts`: `cancelOrder` must invalidate `Orders` and `listOrders` must provide `Orders`; a typo in a tag name fails silently. (3) If the GET happened but showed CONFIRMED, the backend had not yet moved the order: cancellation of a CONFIRMED order compensates through the saga, so the status is eventually consistent; the fix is polling or a status indicator, not a client hack. (4) Add a failing test first: the API-layer test pattern in `store/api.test.ts` (cancel then assert the list endpoint was hit again) is the template.

**Trade-offs and failure modes.**
* Forcing `refetchOnMountOrArgChange` everywhere hides tag mistakes at the price of extra traffic.
* Manually mutating the cache to show CANCELLED would be wrong if the backend rejects the cancel with 409.

**Likely follow-ups.**
* Why does the detail screen update but the list not? The detail screen uses an id-specific tag; the list uses the collection tag. Both must be invalidated.
* How do you prove the fix? A unit test with MSW counting the GETs, then the Playwright cancel spec.

### Q154. How do `useEffect` dependency arrays and cleanup functions work, and where does this app use them?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** React hooks

**Short answer.** An effect runs after render and again whenever a value in its dependency array changes; the function it returns runs before the next run and on unmount. Missing dependencies cause stale closures; missing cleanup leaks timers and listeners.

**Detailed answer.** `hooks/useSessionExpiry.ts` sets one `setTimeout` until the token expires, with `[expiresAt, dispatch]` as dependencies and `clearTimeout` as cleanup, so a new session replaces the old alarm. `hooks/useDebouncedValue.ts` restarts a timer whenever the serialized value changes and clears it on cleanup. The repo also avoids effects where possible: `useListQuery` and `useOrderPolling` adjust their small state during render, the documented pattern for state derived from other state, instead of an effect that sets state after a render (which costs an extra commit and can show a flash of stale data). `eslint-plugin-react-hooks` is on, and lint reports 0 errors.

**Trade-offs and failure modes.**
* Silencing the exhaustive-deps rule to "fix" a loop usually hides a bug; restructure instead (move the value into the dependency list or into a ref).
* Effects that fetch data duplicate what RTK Query already does and lose cancellation and de-duplication.
* React 19 StrictMode runs effects twice in development; a cleanup bug shows up there first.

**Likely follow-ups.**
* Angular equivalent? `effect()` tracks signal reads automatically (no array) and `takeUntilDestroyed` handles cleanup.
* When is `useLayoutEffect` needed? Only for measuring or mutating the DOM before paint; nothing in this app needs it.

### Q155. Scenario: A customer double-clicks "Place order" while the network drops. How do you guarantee at most one order?
**Type:** Scenario   **Level:** Advanced   **Topic:** Idempotency from the browser, forms

**Short answer.** Combine three layers: the submit button is disabled while the mutation is in flight, every `POST /orders` carries an `Idempotency-Key`, and the key is reused only when the previous outcome was unknown and the body is unchanged. The server replays the original response for the same key and body and answers 422 for the same key with a different body.

**Detailed answer.** `IdempotencyKeyHolder` in `features/orders/orderForm.ts` computes a fingerprint of the request (customer, items sorted by product id, address). `keyFor` returns the pending key when the fingerprint is unchanged, otherwise a new one; `settle()` forgets the key after a definite answer (2xx or 4xx). A network error or a 5xx leaves the key pending, so the retry reuses it. `POST` is never retried by `omsBaseQuery` (only GET and HEAD are), so a retry is always the user's decision, with the key preserved. Proof: the test "retry after a lost response REUSES the key" in `features/orders/orders.test.tsx` checks keys across four attempts, and the Playwright duplicate-submit spec passes on both UIs; the backend side is `IdempotencyApiTest` (including the concurrent same-key race).

**Trade-offs and failure modes.**
* Generating the key per click defeats idempotency; generating it once per page load would collide after a body change.
* The holder lives in component state: a full page reload loses the pending key, so a retry after reload could create a second order if the first request did reach the server. A key persisted in `sessionStorage` would close that gap; not done.
* The key table has a retention window (24 h by default), after which a replay is a new order.

**Likely follow-ups.**
* Why sort items in the fingerprint? So reordering lines is not seen as a different body.
* What does the user see on a replay? A notice that the server replayed an earlier identical submission (covered by a test).

### Q156. Scenario: The order detail page keeps hitting the API after you navigate away, and the request rate grows on a slow network. How do you design polling correctly?
**Type:** Scenario   **Level:** Advanced   **Topic:** Polling, RTK Query, performance

**Short answer.** Let the data layer own the timer so unmounting releases it, grow the interval with backoff, and stop on a terminal status, on error and on user request. In this app `useOrderPolling` recomputes RTK Query's `pollingInterval` after each completed fetch (1.5 s times 1.5 to the n, capped at 8 s) and sets it to 0 when polling should stop.

**Detailed answer.** The hand-rolled alternative (`setInterval` in an effect without cleanup) leaks one timer per mount and keeps polling a screen nobody sees. In `hooks/polling.ts` the interval is a pure function of a small state (attempt count, stopped, errored, terminal), the state is adjusted during render when a new fetch completes (`fulfilledTimeStamp` changes), and RTK Query releases its subscription and timer on unmount. `backoffDelay` is a pure function tested in `hooks/hooks.test.tsx` ("grows by 1.5x per attempt and is capped"); `orders.test.tsx` covers polling until a terminal status and the Stop and Refresh buttons. A customer opening another customer's order gets a 404 and polling stops.

**Trade-offs and failure modes.**
* Polling is simple but wasteful at scale; server-sent events or WebSockets would push status changes, at the cost of a stateful gateway path.
* A cap of 8 s bounds the staleness a user can see; a longer cap saves traffic but feels slower.
* Without jitter many tabs synchronize; this app does not add jitter (a stated gap).

**Likely follow-ups.**
* Does a background tab keep polling? Yes; `refetchOnFocus` or the Page Visibility API would pause it, not implemented.
* Why one endpoint for order, history and saga? `getOrderSnapshot` fetches them in parallel so the screen never shows a half-updated state.

### Q157. Why use Redux Toolkit slices with pure helper functions and a serializable `ApiError`, instead of plain reducers or an `Error` class?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Redux Toolkit design

**Short answer.** Redux state and actions should be serializable and updates predictable. Toolkit slices use Immer so reducers can be written as "mutations" that produce immutable updates; extracting the logic into pure functions keeps it testable without a store; and `ApiError` is a plain object because class instances do not belong in the store or the RTK Query cache.

**Detailed answer.** `cartSlice.ts` implements `addToCart`, `setQuantity` and `removeFromCart` as pure functions over arrays and calls them inside reducers; `slices.test.ts` tests them without React or a store. `authSlice.loginSucceeded` uses a `prepare` callback to compute the absolute expiry time, so the reducer itself stays pure (no `Date.now()` inside). `core/apiError.ts` turns problem+json failures into a plain `ApiError` with `status`, `code`, `userMessage`, `correlationId`, `fieldErrors` and `retryAfterSeconds`; because it is serializable, Redux DevTools, the serializability check and the cache all work. `extraReducers` on the cart reacts to `loggedOut`, so one action clears the session and the cart.

**Trade-offs and failure modes.**
* Immer hides mutation; accidentally returning a new value and mutating the draft in one reducer is an error.
* Storing a `Date`, `Map` or class in state triggers the serializability warning and breaks persistence.
* The Angular app uses an `ApiError` class and signal stores; the shapes are the same, the mechanisms differ.

**Likely follow-ups.**
* Why `createSelector`? It memoizes derived values (`selectIsStaff`, `selectCartCount`) so components re-render only when the result changes.
* Where is the token mirrored to `sessionStorage`? A small middleware in `store/index.ts`, not inside a reducer, because reducers must be pure.

### Q158. Scenario: The backend adds a field to the order response. The Angular UI renders it, the React UI does not show it, and nobody notices for a week. How does contract drift happen between two front ends, and how do you prevent it?
**Type:** Scenario   **Level:** Advanced   **Topic:** Dual front ends, API contract drift

**Short answer.** Each app keeps its own hand-copied TypeScript types (`frontend/oms-ui-react/src/models.ts` and the Angular models), so nothing forces them to follow `specs/openapi/*.json` or each other. Detect drift with shared end-to-end specs and contract checks, and remove it by generating types from the OpenAPI export, which this project has not done yet.

**Detailed answer.** The decision to copy rather than share is ADR-0013: a shared package would add packaging overhead for two frameworks that share only types and a few pure functions. The price is drift risk. What exists today: the same 18 Playwright specs run against both UIs (`E2E_UI`), so a flow that depends on a changed field fails for the UI that lacks it; the contract itself is `docs/architecture/00-shared-contract.md`; and `scripts/export-openapi.mjs --check` detects drift between the service code and the committed OpenAPI export (no drift on 2026-10-18). What does not exist: type generation from `specs/openapi/*.json`, so a new optional field simply goes unrendered in the app that was not updated. Prevention order: (1) generate both type sets from the exported OpenAPI; (2) add a contract test that fails when the export changes without a models change; (3) keep the PR checklist item "change both UIs or state the gap" (`docs/governance/code-review-checklist.md`).

**Trade-offs and failure modes.**
* Additive-only API changes (an inside-`/api/v1` rule) mean drift usually shows as a missing feature, not a crash; the dangerous case is a field turning nullable (defect D-03: REJECTED orders have null prices).
* Generated types can lie too: they describe the schema, not the behaviour.
* Parity specs cover 18 flows, not every screen.

**Likely follow-ups.**
* Why not one shared core package? ADR-0013 rejects it for this size; revisit when more than types and pure helpers are shared.
* How would you notice a removed field? A removed field fails the specs for both UIs; an added one only for a UI whose spec asserts it.

### Q159. Scenario: A security review flags `style-src 'unsafe-inline'` in the React UI's Content Security Policy. Explain why it is there and what you would do.
**Type:** Scenario   **Level:** Advanced   **Topic:** CSP, MUI and Emotion, front-end security

**Short answer.** MUI uses Emotion, which injects `<style>` elements at runtime, so a policy without `'unsafe-inline'` for styles blocks it. Scripts stay strict (`script-src 'self'`, no inline scripts in the build). The proper fix is an Emotion cache with a per-response nonce and `style-src 'nonce-...'`; this project has not done it, and the Angular Material app needs the same exception today.

**Detailed answer.** The header in `frontend/oms-ui-react/nginx.conf` reads `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. Residual risk: an HTML injection could add inline styles (UI redressing, CSS-based data exfiltration through attribute selectors); injected script remains blocked. Options, safest first: (1) nonce-based Emotion cache plus nginx generating the nonce per response (needs templating, so the static image becomes dynamic); (2) hash-based styles (brittle with runtime styles); (3) keep `'unsafe-inline'` for styles, document it, and rely on no `dangerouslySetInnerHTML`, no CDN assets and `script-src 'self'` (current choice). Choose (1) for a production release; (3) is acceptable only with an accepted risk entry. Honest status: the header was read from the file; the image was never built, so the live header was not observed.

**Trade-offs and failure modes.**
* A nonce must be unpredictable and different on every response; a static nonce is the same as `'unsafe-inline'`.
* Switching UI libraries to avoid runtime styles (CSS modules) removes the exception at the cost of the component kit.
* Removing the exception without testing breaks styling silently in production.

**Likely follow-ups.**
* Does the exception affect XSS from scripts? No, scripts are controlled by `script-src`.
* How do you verify the header? Build the image and request it with `curl -I`; not done here because Docker was unavailable.

### Q160. Scenario: A reviewer objects that the access token sits in `sessionStorage`. Defend or change the design.
**Type:** Scenario   **Level:** Advanced   **Topic:** Token storage, XSS, BFF

**Short answer.** Any script on the origin can read `sessionStorage`, so an XSS bug steals the token. This app keeps the token in Redux memory and mirrors it to `sessionStorage` (per tab, cleared on close) only so a reload does not sign the user out, and documents the trade-off. The production answer is authorization code with PKCE through a backend-for-frontend that keeps the token server-side behind an HttpOnly, Secure, SameSite cookie.

**Detailed answer.** Options with the safest as the default: (1) BFF with HttpOnly cookies: JavaScript never sees a token; costs a stateful component, CSRF protection (SameSite plus anti-forgery) and session storage. (2) Memory only: no persistence, so a reload logs the user out (or needs a silent refresh); XSS can still call the API while the page is open but cannot take the token away. (3) Memory plus `sessionStorage` (current): convenient, shorter exposure than `localStorage`, still readable by script. Controls that exist today: the token is attached only by `omsBaseQuery` to same-origin API calls and never to `/auth/token`; `returnUrl` accepts only same-app relative paths; no `dangerouslySetInnerHTML`; `script-src 'self'`; a logout or 401 resets the API cache and cart (`store/index.ts`). The demo issues HS256 tokens from a dev endpoint; a real IdP would add short lifetimes and refresh. I would present option 1 as the production design and keep 3 only for the demo.

**Trade-offs and failure modes.**
* `localStorage` persists across tabs and restarts: worse exposure, never used here.
* Cookies reintroduce CSRF; the BFF must handle it.
* A BFF is an extra deployable that is not in this repository.

**Likely follow-ups.**
* Does the backend trust the UI? No: it validates the JWT and authorizes every call; route guards are UX only.
* What stops an attacker with the token? Short lifetime, role checks, ownership checks (404 for foreign orders); revocation is a gap in the demo.

### Q161. How would you approach performance in this React app, and what has actually been measured?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** React performance

**Short answer.** Measure first, then fix: code-split by route, avoid fetching what RTK Query already caches, keep state close to where it is used so fewer components re-render, and memoize only where profiling shows a cost. In this repo no performance measurement exists; only structural choices are in place.

**Detailed answer.** Structural choices: all 14 screens load through `React.lazy` in `routes.tsx` with one `Suspense` in the shell; RTK Query de-duplicates identical requests and caches for 30 seconds after the last use; selectors are memoized with `createSelector`; list filters are debounced (`useDebouncedValue`, 300 ms) so typing does not fire a request per key; screen-local state stays out of the global store so unrelated screens do not re-render on a keystroke. Not done and not measured: bundle size budgets, Lighthouse or Web Vitals runs, React Profiler traces, virtualized tables for long lists (pages are server-side paginated at 20 rows instead). Say so plainly; never quote numbers that were not measured.

**Trade-offs and failure modes.**
* `React.memo`, `useMemo` and `useCallback` everywhere add complexity and can hide bugs when dependencies are wrong; use them after a profile.
* Server pagination caps DOM size but depends on the backend's whitelisted sort and page limits.
* Large global state objects re-render many subscribers; selectors that return new objects every time defeat memoization.

**Likely follow-ups.**
* Angular counterpart? `OnPush` (not used here) and signals; the React app re-renders by default and relies on the techniques above.
* How would you find a slow screen? React Profiler for render cost, the Network tab for request waterfalls, then Lighthouse for load metrics.

### Q162. What do the 207 Vitest tests prove, why mock the network with MSW, and what do they not prove?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Testing React, MSW, coverage

**Short answer.** They prove that the pure logic (slices, selectors, reducers, schemas, idempotency holder), the API layer (headers, retries, error handling, invalidation) and every screen behave as intended when the browser and the server behave as the mocks say. They do not prove real browser behaviour, accessibility, visual layout or the real backend; the Playwright specs cover the first and the live runs the last.

**Detailed answer.** Tests render the whole app at a URL with an isolated store (`src/test/render.tsx`) and answer real `fetch` calls with Mock Service Worker (`src/test/server.ts`), so nothing inside the app is mocked: guards, routing, forms, dialogs and toasts run for real. Testing Library queries by role and label, so a test fails if the accessible name changes. Pure logic is tested without React. Results on 2026-10-19: 207 passed in 9 files; coverage 96.3 percent statements, 93.5 percent branches, 94.5 percent functions, 96.7 percent lines. Coverage measures executed lines, not correct assertions; the idempotency and REJECTED-order tests were written to the behaviour, not to the coverage number. Remaining noise: three harmless "not wrapped in act(...)" notices from async toasts and polling.

**Trade-offs and failure modes.**
* jsdom is not a browser: layout, focus quirks and CSS are not exercised; the Playwright specs run in desktop Chrome only.
* Mocks can drift from the backend; the live-stack runs (18/18 for both UIs) and the OpenAPI export check cover that.
* Snapshot tests were avoided because they pin markup rather than behaviour.

**Likely follow-ups.**
* Why not mock modules with `vi.mock`? It couples tests to implementation and skips the code under test (interceptors, tags).
* What would you add? An axe check in Playwright, a contract test against the OpenAPI export, and a mobile viewport spec.

### Q163. Scenario: A customer logs out on a shared kiosk; the next user signs in and briefly sees the previous customer's orders. How do you prevent it and prove it?
**Type:** Scenario   **Level:** Advanced   **Topic:** Cache hygiene, security, RTK Query

**Short answer.** On logout (user-initiated or a 401) clear everything that could hold the previous user's data: the RTK Query cache, the cart and the persisted session. This app does it with one action: `loggedOut` triggers a listener middleware that calls `omsApi.util.resetApiState()`, the cart slice clears itself through `extraReducers`, and a middleware removes the `sessionStorage` entry.

**Detailed answer.** Without the reset, RTK Query keeps cached responses for 30 seconds after the last subscriber unmounts, so a fast sign-in could render the previous user's list for a moment. The wiring is in `createAppStore()` (`store/index.ts`): a listener on `loggedOut`, the cart's `extraReducers` case, and the persistence middleware that writes `null` when the session becomes null. Proof: `store/api.test.ts` has "logout action resets the API cache" and "mirrors login to sessionStorage, restores it in a new store, and clears it on logout"; `store/slices.test.ts` checks that the cart reducer empties on `loggedOut`. Backend defence in depth: the new user's token can only fetch their own orders (other customers' orders return 404), so even a stale cache is the only leak path, which the reset closes.

**Trade-offs and failure modes.**
* A browser back button can show a bfcache snapshot of the previous page; `Cache-Control: no-store` on authenticated pages would address it (not verified here).
* Clearing only the token and not the cache is the classic bug.
* Any new slice that holds user data must also handle `loggedOut`.

**Likely follow-ups.**
* What about multiple tabs? `sessionStorage` is per tab, so another tab stays signed in until its token expires; cross-tab logout is not implemented.
* Where does the 401 path log out? `omsBaseQuery` dispatches `loggedOut('expired')` for a 401 that is not the login call.

### Q164. Scenario: All 18 end-to-end specs pass against the mock backend for the React UI, but one fails against the live stack. Why can that happen and what do you do?
**Type:** Scenario   **Level:** Intermediate   **Topic:** End-to-end testing, mock versus live, dual front ends

**Short answer.** A mock only knows what its author thought of, so it can hide real behaviour (null fields, headers, rate limits, timing). Treat the live failure as the more valuable signal: reproduce it, decide whether the UI or the mock is wrong, fix the cause, and keep the shared spec unchanged.

**Detailed answer.** The mock backend (`tests/e2e/mock-backend/server.js`) proves the UI against the contract only; the live run drives UI to gateway to real services on embedded Kafka and H2. History in this repo: the first live Angular run found two real defects that the mock hid, including D-03 (REJECTED orders have null sku, name and prices, so `null.toFixed` blanked the order page); the React UI renders `-` for those fields and has a test for it. A triage routine: (1) rerun the failing spec alone with the trace; (2) compare the live response to the mock's with the correlation id; (3) check the environment first, for example a foreign process holding port 18080 (the 2026-10-19 runs used `OMS_GATEWAY_PORT=18085` for exactly that reason, and `run-local.sh` now refuses to start when a port is taken); (4) fix the UI or update the mock to match the real contract; (5) rerun all four configurations (React and Angular, mock and live). The specs are shared on purpose: a fix that makes one UI pass by editing the spec would destroy the parity evidence.

**Trade-offs and failure modes.**
* Live runs are slow and need the whole stack (about 2.5 minutes to start); run them before release, not on every commit.
* Rate limits (20 burst, 5 per second on `POST /orders`) can fail specs that create many orders; the specs are serial with one worker for that reason.
* Only desktop Chrome was used; other browsers and mobile viewports are untested.

**Likely follow-ups.**
* How do you keep the mock honest? Generate its fixtures from the OpenAPI export or record real responses; today they are hand-written.
* Why run Angular again? To keep a baseline: if both UIs fail the same spec, suspect the backend or the environment.

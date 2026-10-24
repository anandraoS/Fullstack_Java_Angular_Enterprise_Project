# React, Redux Toolkit and RTK Query in this project, mapped to Angular, RxJS and Signals

**Read this first: this repository now contains both front ends.** The original brief mandated Angular, so `frontend/oms-ui` (Angular 20, Angular Material, RxJS 7.8, TypeScript 5.9, signal stores, no NgRx) was built first (ADR-0012). The user later explicitly asked for a React frontend as well, so `frontend/oms-ui-react` was added as a second, feature-equivalent UI on the same gateway contract (ADR-0013): React 19, TypeScript strict, Vite, Redux Toolkit with RTK Query, React Router 7, React Hook Form with Zod, MUI, and Vitest with Testing Library and MSW. Every snippet in this chapter, React and Angular, is copied from a real file by the PDF build (the build fails if a snippet no longer exists). Evidence from the 2026-10-19 run: 207 of 207 Vitest tests pass (coverage 96.3 percent statements), ESLint 0 errors, build OK, `npm audit` 0 vulnerabilities, and the same 18 Playwright specs pass against the React UI (mock backend 18/18, live stack 18/18) and the Angular UI (18/18 and 18/18). Not done for the React UI: the Docker image, the compose profile, the Kubernetes manifest, CI runs, and any axe, Lighthouse or screen-reader audit. This chapter lets you explain the same behaviour in both vocabularies and defend why two UIs exist.

## Big picture and kid-friendly analogy

Two schools teach the same subject with different classroom rules.

* **React** is a school where, whenever anything on the whiteboard changes, the teacher erases the whole board and redraws it from the lesson plan (a re-render), then a very fast helper compares the new drawing with the old one and fixes only the differences (the virtual DOM diff). Because the whole drawing is redone, you must be careful what the teacher remembers between redraws: that is why there are rules about hooks and dependency arrays.
* **Angular with signals** is a school where each number on the board has its own little light bulb. When one number changes, only the bulbs connected to it flash, and only those bits of the board are rewritten. Nobody redraws everything, and the connections are discovered automatically by watching who looked at which number.
* **Redux** is a school-wide notice board with strict rules: you may not scribble on it yourself; you hand a note ("action") to the office clerk (the reducer), who writes a new notice board and throws away the old one. Everybody reads from the same board.
* **Angular services with signals** are class notebooks: each class (feature) has its own notebook, one shared notebook (auth) is passed round when needed by the stockroom clerk (dependency injection), and only the owner of a notebook can write in it.
* **Hooks versus dependency injection**: in React you ask the teacher for tools while you are standing in your own seat during class (hooks must be called in a fixed order inside components). In Angular the stockroom clerk (injector) delivers tools when the class is set up.

| Analogy | React and Redux | Angular here |
|---|---|---|
| Erase and redraw, diff the drawing | Re-render plus virtual DOM | Change detection plus signal-driven view updates |
| Bulb per number | (none: a component re-renders; `createSelector` memoizes derived data) | `signal()` and `computed()` |
| Notice board, notes to the clerk | Store, actions, reducers | Store class with signals, pure update functions |
| Stockroom clerk | Context, hooks | `inject()`, providers |
| Whole-school vs class notebook | Global store vs component state | `providedIn: 'root'` vs component `providers` |

## Beginner tier: components, props and local state

### Example 1: a presentational component with props

React version, from `frontend/oms-ui-react/src/components/StatusChip.tsx` (real code; the component function runs again on every render):

{{code:frontend/oms-ui-react/src/components/StatusChip.tsx|^export function StatusChip|12|tsx}}

Angular version, from `frontend/oms-ui/src/app/shared/components/status-chip.ts`:

```ts
export class StatusChip {
  readonly status = input.required<string>();
  readonly tone = computed(() => toneFor(this.status()));
  readonly glyph = computed(() => GLYPH[this.tone()]);
  readonly label = computed(() => this.status().replace(/_/g, ' '));
}
```

The mapping: props become inputs (`input.required<string>()` is the signal-input form; the older decorator form is `@Input()`); derived values that React recomputes in the function body on every render (or memoizes with `useMemo`) are `computed()` fields that are cached and re-evaluate only when `status` changes; JSX is replaced by a template (inline in this file, a separate `.html` in `app.html`); callbacks passed as props become `output()` emitters (for example `retry` and `sortChange` in `shared/components/data-table.ts`). Differences to say aloud: an Angular component class is created once per instance and does not re-run on updates (there is no "render function"), so nothing like the stale-closure problem exists; template expressions must be side-effect free; and a component declares exactly what it uses in `imports`, where React uses ES module imports at the top of the file.

### Example 2: local state, conditional rendering and lists

React version, from `frontend/oms-ui-react/src/layout/AppShell.tsx` (real code: the role list comes from the Redux store through `useAppSelector`, `useState` holds the menu anchor, the list is rendered with `map` and a `key`):

{{code:frontend/oms-ui-react/src/layout/AppShell.tsx|const authed = useAppSelector|10|tsx}}

{{code:frontend/oms-ui-react/src/layout/AppShell.tsx|<nav aria-label="Main navigation">|8|tsx}}

Angular version. State is a signal (`frontend/oms-ui/src/app/app.ts`: `protected readonly navOpen = signal(true);`), and rendering uses built-in control flow (`frontend/oms-ui/src/app/app.html`):

```html
@if (auth.isAuthenticated()) {
  <mat-sidenav ...>
    <nav>
      <mat-nav-list>
        @for (item of nav(); track item.path) {
          <a mat-list-item [routerLink]="item.path" routerLinkActive="active-link" ...>
            {{ item.label }}
          </a>
        }
      </mat-nav-list>
    </nav>
  </mat-sidenav>
}
```

The mapping: `useState` becomes `signal()`; updating is `set` or `update(fn)` (the same functional-update idea as `setState(prev => ...)`), and the component does not re-render, the dependent template bindings refresh. The `key` prop becomes `track` in `@for`, and it is mandatory in Angular. Short-circuit `&&` and ternaries in JSX become `@if` and `@else`. Angular reads signals by calling them (`nav()`), a visible reminder that you are subscribing. A hook rule that has no Angular equivalent: you may call `inject()` or read signals conditionally in templates because there is no ordered hook list; the constraint is the injection context (constructors, field initializers, guards), not call order.

## Intermediate tier: derived state, effects, context and routing

### Example 1: selectors and effects (`useMemo` and `useEffect` against `computed` and `effect`)

React version (real code). Derived values are computed during render or in a memoized selector, and the "effect" is a subscription with cleanup. `useOrderPolling` in `frontend/oms-ui-react/src/hooks/polling.ts` lets RTK Query poll, recomputing `pollingInterval` after every fetch; `useDebouncedValue` shows a classic `useEffect` with a dependency array and a cleanup function; `createSelector` in `authSlice.ts` is the `computed()` of Redux:

{{code:frontend/oms-ui-react/src/hooks/polling.ts|^export function useOrderPolling|10|tsx}}

{{code:frontend/oms-ui-react/src/hooks/useDebouncedValue.ts|^export function useDebouncedValue|9|tsx}}

{{code:frontend/oms-ui-react/src/store/authSlice.ts|^export const selectIsStaff|3|tsx}}

Angular version, from `frontend/oms-ui/src/app/features/orders/order-detail.ts` and `order-detail.store.ts`:

```ts
// order-detail.ts
readonly id = input.required<string>();

constructor() {
  // useEffect-like: restart when the route id changes (untracked: only `id` is a dependency)
  effect(() => {
    const id = this.id();
    untracked(() => this.store.start(id));
  });
}
```

```ts
// order-detail.store.ts
readonly order = computed(() => this._snapshot()?.order ?? null);
readonly terminal = computed(() => isTerminal(this.order()));
readonly cancellable = computed(() => canCancel(this.order()));
```

The mapping: `useMemo` and Redux selectors (including `reselect`) become `computed()`, with **no dependency array**: dependencies are the signals read during the computation, found automatically. `useEffect` becomes `effect()` for side effects that follow signal changes, again with no dependency array; to exclude reads from tracking you wrap them in `untracked()` (here, so that starting the store, which reads and writes other signals, does not make the effect depend on them). Cleanup: React returns a cleanup function; Angular's `effect` callback receives an `onCleanup` registration function, and the polling here is torn down by the store (`stopPolling()` called from `start()` and from a `destroyRef.onDestroy` hook) and by `takeUntil(stop$)` in the stream. Two traps in both worlds: an effect that writes the state it depends on can loop, and effects used to derive state are a code smell (use `computed` or `useMemo`). Differences: React effects run after paint on each relevant render, may run twice in Strict Mode development, and capture values from the render they were created in; Angular effects run when the framework schedules them during change detection and always read current signal values. For asynchronous IO that needs cancellation, debouncing or retry, Angular code in this repo uses RxJS inside the effect or store, not the effect itself; that is the role `useEffect` plus `AbortController`, RTK Query, or `redux-saga` would play in React.

### Example 2: context versus dependency injection, and router guards against protected routes

React version (real code), `frontend/oms-ui-react/src/components/guards.tsx` and the route table in `routes.tsx`. The guard is a layout route that renders `<Outlet />` or redirects, instead of a function returning a `UrlTree`:

{{code:frontend/oms-ui-react/src/components/guards.tsx|^export function RequireRole|4|tsx}}

{{code:frontend/oms-ui-react/src/routes.tsx|<Route element=.<RequireAuth />|15|tsx}}

Angular version, `frontend/oms-ui/src/app/core/auth/guards.ts`:

```ts
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  if (!auth.isAuthenticated()) return router.createUrlTree(['/login']);
  const wanted = route.data['roles'] as Role[] | undefined;
  return auth.hasAnyRole(wanted) ? true : router.createUrlTree(['/forbidden']);
};
```

with the route declaration in `frontend/oms-ui/src/app/app.routes.ts`:

```ts
{
  path: 'inventory',
  canActivate: [roleGuard],
  data: { roles: STAFF },
  title: 'Inventory',
  loadComponent: () => import('./features/inventory/inventory').then((m) => m.Inventory),
},
```

The mapping: Context plus a provider component plus `useContext` becomes an `@Injectable({ providedIn: 'root' })` class and `inject(AuthStore)` (`core/auth/auth.store.ts`); there is no provider tree to nest, scoping is done with the `providers` array (a component can provide its own store instance: `providers: [OrderDetailStore]`), and consumers read signals, so only the parts of the UI that read `isAuthenticated()` update (the Context re-render-everything problem does not arise). A protected-route wrapper component or a React Router loader that redirects becomes a functional guard attached to the route and returning a `UrlTree`; `<Navigate>` becomes `router.createUrlTree`. `React.lazy` plus `Suspense` becomes `loadComponent: () => import(...)`, with the router handling loading. Route params: React Router's `useParams` becomes an `input()` bound by `withComponentInputBinding()` (`OrderDetail.id`). In both frameworks, **a protected route is a UX feature, not security**: the repo's guard comments, `docs/architecture/angular-architecture.md` section 5, and chapter 10 all say the backend enforces authorization.

## Advanced tier: stores, reducers, async flows, middleware and rendering performance

### Example 1: Redux slice, reducers and thunks against signal stores with pure functions

React and Redux Toolkit version (real code). The `cart` slice (`cartSlice.ts`) uses Immer-style reducers over pure helper functions and reacts to the auth slice's `loggedOut` action; `store/index.ts` wires a listener middleware that drops the whole RTK Query cache on logout; `store/api.ts` declares the server-state endpoint with its cache tags:

{{code:frontend/oms-ui-react/src/store/cartSlice.ts|^export const cartSlice|28|tsx}}

{{code:frontend/oms-ui-react/src/store/index.ts|const listener = createListenerMiddleware|8|tsx}}

{{code:frontend/oms-ui-react/src/store/api.ts|createOrder: b.mutation|9|tsx}}

Angular version, `frontend/oms-ui/src/app/core/state/list-store.ts` (the doc comment on the class literally says "React/Redux mapping: slice + selectors + a request effect"):

```ts
export const initialListState = <T>(): ListState<T> => ({ status: 'idle', data: null, error: null });
export const listLoading = <T>(s: ListState<T>): ListState<T> => ({ ...s, status: 'loading', error: null });
export const listLoaded = <T>(data: Page<T>): ListState<T> => ({ status: 'loaded', data, error: null });
export const listFailed = <T>(s: ListState<T>, error: ApiError): ListState<T> => ({ ...s, status: 'error', error });

// inside ListStore
readonly items = computed(() => this._state().data?.content ?? []);
readonly loading = computed(() => this._state().status === 'loading');
```

and the request side:

```ts
this.reload$
  .pipe(
    startWith(undefined),
    switchMap(() => {
      this._state.update(listLoading);
      return this.fetchFn(this._query()).pipe(
        map((page) => listLoaded(page)),
        catchError((e: unknown) => of(listFailed(this._state(), normalizeError(e)))),
      );
    }),
    takeUntilDestroyed(destroyRef),
  )
  .subscribe((s) => this._state.set(s));
```

The mapping, row by row. A Redux **slice** is a store class whose state is a `signal<ListState<T>>`. **Reducers** are the exported pure functions (`listLoading`, `listLoaded`, `listFailed`, `withFilters`, `withPage`, `withSort`), and they are unit-tested without TestBed, exactly as reducers are tested without a store. They return new objects rather than mutating, whereas RTK reducers use Immer to let you write mutations. **Actions and `dispatch`** become method calls on the store (`setFilters`, `setPage`, `toggleSort`, `refresh`); there is no action log. **Selectors** become `computed`. A **thunk** (`createAsyncThunk`) or a **saga** becomes an RxJS pipeline: `switchMap` provides latest-wins cancellation (the stale-response guarantee that you would otherwise get from `AbortController`, request ids or RTK Query's cache handling), and `catchError` inside the projection keeps the pipeline alive after a failure. The `useSelector` hook becomes reading the signal in a template or `computed`. **RTK Query or React Query** has no built-in equivalent here: this project does not cache server state, it refetches (ADR-0012: "server state is refetched, not mirrored"); caching, deduplication, invalidation and optimistic updates would be hand-rolled, which the ADR lists as a known drawback.

When would you reach for NgRx instead? The ADR's revisit triggers are the honest answer: more than a few stores writing the same entity, a need for time-travel or devtools, cross-tab undo, or an offline write queue. NgRx SignalStore is the first step (same signal model), full NgRx (actions, reducers, effects, selectors, Redux DevTools) only for event-sourced client logic. In an interview, saying "I would not add NgRx for a dozen screens whose data is refetched, and here is the trigger that would change my mind" is a stronger answer than reciting its parts.

### Example 2: middleware and rendering performance (`axios` interceptors, RTK middleware and `React.memo` against `HttpInterceptorFn` and change detection)

React version (real code). `frontend/oms-ui-react/src/store/baseQuery.ts` is the single place where every request gets its correlation id and bearer token, GET-only retries, error normalization, the 401 logout and the 429 toast. It plays the role of the Angular interceptor chain and of Redux middleware:

{{code:frontend/oms-ui-react/src/store/baseQuery.ts|^const rawBaseQuery: Raw|14|ts}}

{{code:frontend/oms-ui-react/src/store/baseQuery.ts|^export const omsBaseQuery|36|ts}}

Angular version, `frontend/oms-ui/src/app/core/http/interceptors.ts`:

```ts
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const apiBase = inject(ConfigService).apiBaseUrl;
  const token = inject(AuthStore).token();
  if (!token || !isApiCall(req.url, apiBase) || isTokenCall(req.url)) return next(req);
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
```

registered once in `frontend/oms-ui/src/app/app.config.ts` as `provideHttpClient(withInterceptors([correlationInterceptor, authInterceptor, retryInterceptor, errorInterceptor]))`. The mapping: Axios interceptors, `fetch` wrappers, RTK Query's `prepareHeaders` and Redux **middleware** concerns (auth headers, retries, error normalization, logging) all become functional interceptors. Requests flow through the array left to right and responses right to left; requests are immutable so you `clone()`. Redux middleware that reacts to actions (analytics, side effects after `dispatch`) has no direct analogue because there are no actions; the closest are effects, subscriptions inside the store, or an `HttpInterceptorFn` for HTTP-triggered concerns.

Rendering performance deserves an honest paragraph. In React, `React.memo`, `useCallback` and `useMemo` stop a component re-rendering when props are referentially equal; mistakes show up as unnecessary renders or stale closures. In Angular the counterpart is `ChangeDetectionStrategy.OnPush`, which skips a component unless an input reference changes, an event fires inside it, a signal it reads changes, or `markForCheck()` is called. **This repository does not use `OnPush` anywhere and still runs on zone.js with the default strategy** (`provideZoneChangeDetection({ eventCoalescing: true })` in `app.config.ts`); `docs/architecture/angular-architecture.md` section 2 acknowledges that signals make a zoneless switch feasible later. So if you say "I use `OnPush` like `React.memo`", be able to say that you know how it works but that this codebase has not adopted it. The `track` expression in `@for` is the equivalent of React's `key`; and the Angular compiler's template function calls (`shortId()`, `lineTotal(i)`) re-run each change-detection pass, the analogue of work done in a React render body, so keep them cheap or use `computed`.

## How this repository implements it

| React or Redux concept | Where in repo | Notes |
|---|---|---|
| Function component and props | `frontend/oms-ui/src/app/shared/components/status-chip.ts` | `input.required()`, `computed()`, no module |
| `useState` | `frontend/oms-ui/src/app/app.ts` (`navOpen`), many features | `signal()`, `set`, `update` |
| `useMemo` / selector | `frontend/oms-ui/src/app/core/auth/auth.store.ts`, `core/state/list-store.ts` | `computed()` fields, no dependency arrays |
| `useEffect` | `frontend/oms-ui/src/app/features/orders/order-detail.ts` | `effect()` with `untracked()`; RxJS for IO |
| Redux slice and reducers | `frontend/oms-ui/src/app/core/state/list-store.ts`, `cart.store.ts` | pure exported functions plus signal store |
| Thunk, saga, RTK Query | `core/state/list-store.ts`, `core/state/polling.ts`, `features/orders/order-detail.store.ts` | `switchMap`, `expand`, `forkJoin`; no query cache |
| Context | `core/auth/auth.store.ts` (`providedIn: 'root'`), `OrderDetailStore` (component `providers`) | DI replaces providers and `useContext` |
| React Router and protected routes | `frontend/oms-ui/src/app/app.routes.ts`, `core/auth/guards.ts` | lazy routes, functional guards, `withComponentInputBinding` |
| `React.lazy` | `loadComponent` in `app.routes.ts` | 14 lazy routes |
| Axios interceptors, middleware | `frontend/oms-ui/src/app/core/http/interceptors.ts` | correlation id, bearer, GET retry, error normalization |
| Controlled forms or React Hook Form | `frontend/oms-ui/src/app/features/login/login.ts`, `orders/order-create.ts`, `orders/order-form.ts` | typed reactive forms, validator functions, server-error mapping |
| Environment variables at build time | `frontend/oms-ui/public/config.json`, `core/config.service.ts` | runtime config loaded by `provideAppInitializer` |
| Error boundary | `core/http/api-error.ts`, `core/notify/toaster.ts`, `provideBrowserGlobalErrorListeners()` | no render-time error boundary equivalent configured |
| Testing Library and Jest | `*.spec.ts`, `src/app/testing/test-utils.ts` | Karma and Jasmine; `HttpTestingController` instead of MSW |
| Redux DevTools | none | listed as a revisit trigger in ADR-0012 |

The React side of the same table (all real files under `frontend/oms-ui-react/src`):

| React or Redux concept | Where in repo | Notes |
|---|---|---|
| Function component and props | `frontend/oms-ui-react/src/components/StatusChip.tsx` | re-renders when props change; glyph plus text |
| `useState`, `useReducer` | `frontend/oms-ui-react/src/layout/AppShell.tsx`, `frontend/oms-ui-react/src/hooks/listQuery.ts` | local state and a pure list reducer |
| Selector (`createSelector`) | `frontend/oms-ui-react/src/store/authSlice.ts`, `frontend/oms-ui-react/src/store/cartSlice.ts` | memoized derived state |
| `useEffect` with cleanup | `frontend/oms-ui-react/src/hooks/useSessionExpiry.ts`, `frontend/oms-ui-react/src/hooks/useDebouncedValue.ts` | timers cleared on change or unmount |
| Redux Toolkit slice | `frontend/oms-ui-react/src/store/authSlice.ts`, `frontend/oms-ui-react/src/store/uiSlice.ts` | Immer reducers over pure helpers |
| RTK Query | `frontend/oms-ui-react/src/store/api.ts`, `frontend/oms-ui-react/src/store/baseQuery.ts` | cache, tags, polling, one base query |
| Listener middleware | `frontend/oms-ui-react/src/store/index.ts` | reset API cache on logout |
| Context and Provider | `frontend/oms-ui-react/src/App.tsx` | Redux `Provider`, MUI `ThemeProvider`, router |
| React Router and guards | `frontend/oms-ui-react/src/routes.tsx`, `frontend/oms-ui-react/src/components/guards.tsx` | layout routes, `React.lazy` screens |
| Error boundary | `frontend/oms-ui-react/src/components/ErrorBoundary.tsx` | per-route reset |
| React Hook Form and Zod | `frontend/oms-ui-react/src/features/orders/orderForm.ts` | schema validation, idempotency key holder |
| Testing Library and MSW | `frontend/oms-ui-react/src/test/server.ts`, `frontend/oms-ui-react/src/test/render.tsx` | whole app rendered against a mock service worker |
| Env variables at build time | `frontend/oms-ui-react/public/config.json`, `frontend/oms-ui-react/src/core/config.ts` | runtime config, no rebuild |


A forms comparison, because React candidates are often asked about it. React typically uses controlled inputs (`value` plus `onChange` bound to state) or a library such as React Hook Form; Angular reactive forms keep a `FormGroup` object outside the template, typed per control, with validators as functions and `valueChanges` as an Observable. From `features/login/login.ts`:

```ts
protected readonly form = this.fb.group({
  username: ['', [Validators.required]],
  password: ['', [Validators.required]],
});
```

and the debounced filter pattern on the catalog (`features/catalog/catalog.ts`) `this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => this.apply());` is what a `useDebouncedValue` hook plus an effect would do in React.

## React implementation deep dives (real code in `frontend/oms-ui-react`)

The three tiers above compared React and Angular side by side. These deep dives stay inside the React app and show the pieces an interviewer will probe: typed hooks, forms and validation, effects with cleanup, error boundaries, idempotent writes, RTK Query design and testing with a mock server. Each example names the file, so you can open it while you answer.

### Beginner tier

**Example B1: typed Redux hooks (the labelled walkie-talkie).** Analogy: in a big school every classroom has a walkie-talkie to the office. If the walkies are unlabelled, you never know which channel carries which kind of message; a labelled walkie tells you exactly what you may ask and what answer shape comes back. `useDispatch.withTypes` and `useSelector.withTypes` give every component a labelled pair, so a wrong action or a wrong state path is a compile error, not a runtime surprise.

{{code:frontend/oms-ui-react/src/hooks/redux.ts|^import|5|ts}}

The Angular counterpart is `inject(AuthStore)`, where the store class is the type. In an interview: "typed hooks are one file, imported everywhere; components never import `useDispatch` directly".

**Example B2: a form validated by a schema (the bouncer with a checklist).** Analogy: a club bouncer checks the same checklist for every guest (name given, age in range); the checklist lives on the door, not in each guest's pocket. React Hook Form manages the inputs and Zod is the checklist: one schema describes every rule, the form shows the messages, and the same checks can be unit-tested without rendering anything. Quantity is held as the raw string and only converted by `buildRequest` when the form is valid, so the user can type freely while validation stays strict:

{{code:frontend/oms-ui-react/src/features/orders/orderForm.ts|^export const orderFormSchema|17|ts}}

Compare with Angular reactive forms: validators there are functions attached to controls in the component; here they are a declarative schema outside the component. Server-side validation still decides: a 400 with `errors[]` is mapped back onto the controls by `toFormPath` (`items[0].quantity` becomes `items.0.quantity`), and unknown fields stay in the banner.

### Intermediate tier

**Example I1: an effect with cleanup (the alarm clock you must switch off).** Analogy: you set an alarm for when your pass expires; if you leave the room (or get a new pass) you must cancel the old alarm, or it will ring at the wrong time. `useSessionExpiry` sets one timeout for the token's expiry and returns a cleanup function; the dependency array `[expiresAt, dispatch]` means "start over whenever the pass changes". It dispatches the same `loggedOut('expired')` action that a 401 does, so there is one logout path:

{{code:frontend/oms-ui-react/src/hooks/useSessionExpiry.ts|^export function useSessionExpiry|11|ts}}

Pitfalls to mention: a missing dependency gives a stale closure (the `react-hooks/exhaustive-deps` lint rule is on in this repo), and a missing cleanup leaks timers, which in tests shows up as "act" warnings. Angular's version is a `timer` stream plus `takeUntilDestroyed`.

**Example I2: an error boundary (the net under the trapeze).** Analogy: acrobats work better with a net; if one falls, the show continues with a short pause instead of an empty tent. React can only catch render-time errors in a class component, so `ErrorBoundary` is the one class in the code base. It resets when the route changes (`resetKey`), so one broken screen does not blank the rest:

{{code:frontend/oms-ui-react/src/components/ErrorBoundary.tsx|^export class ErrorBoundary|30|tsx}}

Angular has no direct equivalent here: its global `ErrorHandler` logs and the template keeps what it rendered. Defect D-03 (a blank order page caused by `null.toFixed` on REJECTED orders) is the real story behind this net and behind the null-tolerant price formatting.

### Advanced tier

**Example A1: an idempotent order form hook (the numbered raffle ticket).** Analogy: you buy a raffle ticket; if the stall's shutter drops before you see the receipt, you come back with the same ticket number so the stall does not charge you twice; if you change the order, you get a new ticket. `IdempotencyKeyHolder` returns the same key while the request body fingerprint is unchanged and the previous outcome is unknown, and a fresh key after a definite answer or a changed body. The server replays the original response for the same key and body, and answers 422 `IDEMPOTENCY_KEY_REUSED` for the same key with a different body. The holder is plain TypeScript, tested without React, and `OrderCreate.tsx` keeps one instance per mounted form:

{{code:frontend/oms-ui-react/src/features/orders/orderForm.ts|^export class IdempotencyKeyHolder|21|ts}}

Interview points: the key is generated per logical submission, never per click; `POST /orders` is never retried by the base query (only GET and HEAD are), so a retry is always a user decision; the submit button is disabled while the mutation is in flight; the fingerprint sorts items so line order does not matter.

**Example A2: RTK Query design (the library card catalogue).** Analogy: a librarian keeps a catalogue of which shelves each book came from; when a book is returned or changed, only the cards pointing at that shelf are marked "out of date". Tags are the shelf labels: `getOrderSnapshot` provides `{ type: 'Order', id }`, and `cancelOrder` invalidates that tag plus `Orders` and `Failed`, so the detail screen, the order list and the failed-orders list refresh themselves without any manual refetch. `getOrderSnapshot` also shows a custom `queryFn`: it fetches order, history and (for staff) saga in parallel and tolerates a failing saga call, which is the React counterpart of the Angular `forkJoin`:

{{code:frontend/oms-ui-react/src/store/api.ts|^    getOrderSnapshot: b.query|19|ts}}

Because the cache is keyed by endpoint and arguments, a late response for an old filter can never overwrite a newer one (the job `switchMap` does in the Angular `ListStore`). On logout the listener middleware calls `resetApiState()`, so the next user of the tab sees nothing from the previous session.

**Example A3: testing against a mock server (the rehearsal with a stand-in audience).** Analogy: you rehearse the school play with classmates acting as the audience, so you can see how the actors react to applause or silence before opening night. Mock Service Worker answers the app's real `fetch` calls, so the test renders the whole app at a URL, clicks like a user, and asserts what is visible; nothing inside the app is mocked. This test proves that a lost response keeps the idempotency key, a changed body gets a new key, and a definite answer gets another one:

{{code:frontend/oms-ui-react/src/features/orders/orders.test.tsx|retry after a lost response|24|tsx}}

Run result on 2026-10-19: this file is part of the 207 passing Vitest tests; the same behaviour is also covered end to end by the Playwright specs for duplicate submit.

**Example A4: two front ends on one contract (drift and CSP).** Parity is enforced by running the same 18 Playwright specs against both UIs (`E2E_UI=angular` or `react`), not by sharing code. The cost is real: every feature is written twice, and the contract types are copied (`src/models.ts` in React, the models folder in Angular), so a new field must be added in both places. Drift would first appear as a failing e2e spec or a wrong rendering, not as a compile error across the two apps; generating the types from `specs/openapi/*.json` is the named improvement. On security, both UIs ship the same nginx CSP with `style-src 'unsafe-inline'` (MUI/Emotion and Angular Material inject styles at runtime) while `script-src 'self'` stays strict; a nonce-based Emotion cache would remove the exception. The token is in Redux memory plus `sessionStorage` in React and the equivalent signal store in Angular, readable by any script on the origin, which is why the production recommendation is authorization code with PKCE and a BFF using HttpOnly cookies.

### Demonstration-only vs production-grade

Both implementations are real and tested, but they are independent: nothing was generated from one to the other, and the types in `frontend/oms-ui-react/src/models.ts` are a hand-copied duplicate of the Angular models (drift is caught only by tests and the shared e2e specs). What is **not** real: any measurement comparing the two frameworks (bundle size, render time, developer productivity), any accessibility audit of either UI, and any run of the React container image or Kubernetes manifest. The mapping tables in `docs/architecture/angular-architecture.md` section 9 and the React README are the quick reference.

### Honest guidance for explaining React experience in an interview

1. **State the fact plainly.** "The brief was Angular; I later added a React and Redux Toolkit UI on the same contract, and the same 18 end-to-end specs pass against both." Point at the numbers (207 unit tests, 18/18 on four configurations) and at what was not run (container image, accessibility audit).
2. **Lead with the principle, then the translation.** Unidirectional data flow, immutable updates, derived state, side effects at the edge: the principles are the same; the Angular spelling is signals, `computed`, `effect`, RxJS and DI.
3. **Name the real differences.** Change detection is not a re-render; effects have no dependency arrays; DI replaces Context; templates replace JSX; RxJS is a first-class part of the platform; Angular ships a router, HTTP client, forms and DI, where React assembles these from libraries.
4. **Do not over-claim Angular depth you do not have.** Point to what the code does and what it does not (no `OnPush`, no NgRx) and say what you would do next.
5. **Use concrete anchors.** `ListStore` is "a Redux slice with selectors and a request effect", `pollUntil` is "a thunk with backoff and cancellation", an interceptor is "middleware", `roleGuard` is "a protected route wrapper", `signal` is "`useState` without re-rendering the component".
6. **Be ready for the "why not NgRx" and "why not React" questions.** The first has an ADR with revisit triggers. The second is not answered by the repo at all (it was an Angular project by requirement); say so, and give your own reasoning (ecosystem, team skills, opinionated structure) rather than inventing history.

## Common mistakes and gotchas

1. **Looking for a dependency array.** `computed` and `effect` track dependencies automatically; the bugs are accidental tracking (wrap in `untracked`) and missed tracking (reading a signal asynchronously, after an `await`, is not tracked).
2. **Mutating state and expecting an update.** `items.push(x)` on an array held by a signal does not notify; use `update(l => [...l, x])`, as `CartStore` does. This is the same bug as mutating Redux state, with a different symptom.
3. **Treating `effect` as `useEffect` for everything.** Fetching on a change is better as an RxJS `switchMap` (cancellation, ordering); deriving state belongs in `computed`.
4. **Expecting a re-render model.** Constructors run once; `ngOnInit` and field initializers do not re-run when inputs change. Use signal inputs with `computed` or `effect`.
5. **Forgetting unsubscription.** React effects return cleanup; Angular subscriptions need `takeUntilDestroyed`, `async`, or `toSignal`.
6. **Putting everything in a global store.** The ADR keeps cross-screen state small (auth, cart) and uses per-screen stores for the rest. Component-scoped providers are the Angular answer to "local but shared among children".
7. **Assuming server-state caching in the Angular app.** There is none there; every screen refetches. The React app has RTK Query (cache keyed by endpoint and arguments, tag invalidation, 30 s keep-unused), so a behaviour that differs between the two UIs after a write (stale list in React if a tag is missing, extra refetch in Angular) is a real class of bug.
8. **Believing guards are security.** Same as protected routes in React: they are cosmetic; the API must enforce.
9. **Using `any` to escape strict templates.** TypeScript strictness is part of the platform's value; keep types.
10. **Claiming Redux DevTools-style debugging exists.** It does not here; debug with signals in the template, logs and the correlation id on every request.
11. **Ignoring `track`.** It is the `key` prop; unstable or index-based identity loses DOM state.
12. **Overstating React experience or understating its transferability.** Both hurt; be specific about projects, state libraries and testing tools you actually used.

## Quick-recall cheat sheet

* Both UIs exist: `frontend/oms-ui` (Angular, mandated by the brief) and `frontend/oms-ui-react` (React, added later at the user's request); all snippets are real files.
* Component: standalone class plus template; props are `input()`; callbacks are `output()`; `key` is `track`; JSX conditionals are `@if` and `@for`.
* `useState` is `signal()`; `useMemo` and selectors are `computed()`; `useEffect` is `effect()` (plus `untracked`, `onCleanup`) or an RxJS pipeline for IO.
* Redux slice is a store class with signals; reducers are exported pure functions; actions are method calls; thunks and sagas are RxJS (`switchMap`, `expand`, `forkJoin`); no devtools, no action log, no query cache.
* Context and `useContext` are DI: `providedIn: 'root'` for singletons, component `providers` for per-instance.
* Router: `loadComponent` is `React.lazy`; functional guards return `UrlTree`; `withComponentInputBinding` gives route params as inputs; guards are UX only.
* Middleware and Axios interceptors are `HttpInterceptorFn`; order in the array is request order; clone requests.
* `React.memo` is `ChangeDetectionStrategy.OnPush`, which this repo does not use; change detection is zone-based with signals.
* Forms: typed reactive forms plus validator functions; `valueChanges` plus `debounceTime` plus `takeUntilDestroyed`.
* NgRx revisit triggers (ADR-0012): several stores writing one entity, devtools or time travel, cross-tab undo, offline write queue; try SignalStore first.
* Interview line: "Same principles, different spelling: immutable state, derived values, effects at the edge. In Angular here that is signals, `computed`, RxJS and DI."

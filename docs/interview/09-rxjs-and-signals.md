# RxJS 7 and Angular Signals: Streams for Input and Output, Signals for State

This chapter explains the two reactive models used in `frontend/oms-ui` and, more importantly, how they divide the work. RxJS 7.8 handles anything that happens over time: HTTP calls, polling, debounced typing, cancellation. Angular signals (`@angular/core` 20.3) hold the current state that templates read. The repo's decision record is `docs/adr/0012-angular-state-management.md`: "Signals in `providedIn: 'root'` stores for cross-screen state, per-screen stores for server-backed screens, RxJS for IO and polling, no NgRx". The bridge between the two is `toSignal`, `takeUntilDestroyed` and one-line `.subscribe(...)` calls that write into signals.

## Big picture and kid-friendly analogy

Think of two things in a house with a garden.

* **An Observable** is a garden hose connected to a tap that somebody else controls. Nothing flows until you turn your end on (subscribe). Water can arrive once, many times, or never, and it can stop with a clean "all done" (complete) or a burst pipe (error). Operators are attachments you screw onto the hose: a filter, a splitter, a timer that only lets water through every five seconds.
* **`switchMap`** is a kid who asked for lemonade, then changed their mind and asked for orange juice. The helper pours out the lemonade jug and only brings the orange juice. Only the latest request matters.
* **`concatMap`** is a lunch queue: one tray at a time, in order. **`mergeMap`** is a canteen with many counters: everyone is served at once, whoever finishes first leaves first. **`exhaustMap`** is a lift: while it is busy going up, extra button presses are ignored.
* **A signal** is a whiteboard in the kitchen showing "how many cookies are left: 7". Anybody can glance at it at any time and always sees the current number. A **computed** is a sticky note under it saying "enough for 3 kids", which rewrites itself when the number changes. An **effect** is a little robot that does something each time the board changes, like sending a text.
* The **subscription leak** is leaving the hose running after you leave the garden.

| Analogy | Concept |
|---|---|
| Hose that flows only when you turn it on | Cold Observable, `subscribe()` |
| Changed order, only latest served | `switchMap` |
| Lunch queue / many counters / busy lift | `concatMap` / `mergeMap` / `exhaustMap` |
| Whiteboard with the current number | `signal()` |
| Sticky note that rewrites itself | `computed()` |
| Robot that reacts to the board | `effect()` |
| Leaving the hose on | Unsubscribed stream, memory leak |

## Beginner tier

### Example 1: a signal store for authentication (signal, computed, asReadonly)

`frontend/oms-ui/src/app/core/auth/auth.store.ts` is the project's smallest complete state container:

```ts
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly _session = signal<Session | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly session = this._session.asReadonly();
  readonly isAuthenticated = computed(() => this._session() !== null);
  readonly roles = computed(() => this._session()?.roles ?? []);
  readonly isStaff = computed(() => this.roles().some((r) => r === 'OPERATOR' || r === 'ADMIN'));
  readonly isAdmin = computed(() => this.roles().includes('ADMIN'));
  readonly token = computed(() => this._session()?.accessToken ?? null);
```

Three habits to point out. First, the writable signal is private (`_session`) and the public property is `asReadonly()`, so only the store can change state; this is the same encapsulation as a Redux reducer, enforced by TypeScript instead of convention. Second, derived values are `computed`: they are lazy, memoized and re-evaluate only when a signal they read changes (`isStaff` depends on `roles`, which depends on `_session`). Third, writes go through methods: `login()` calls `setSession(...)`, `logout()` calls `setSession(null, true)` and navigates. A signal read is a function call, and reading inside a template or a `computed` registers a dependency automatically, which is why `@if (auth.isAuthenticated())` in `app.html` re-renders without any subscription code. The store also schedules an auto-logout timer from the token expiry and clamps it to `MAX_TIMER_MS` because `setTimeout` overflows beyond about 24.8 days; a small but real production detail.

Signals have no `async`, no teardown and no error channel. That is the key difference from Observables and the reason HTTP stays in RxJS: a request has a lifecycle, a signal is only a current value.

### Example 2: debounced typing with RxJS, cleaned up with `takeUntilDestroyed`

`frontend/oms-ui/src/app/features/catalog/catalog.ts` wires a search form to a server-paginated store:

```ts
constructor() {
  this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => this.apply());
}

protected apply(): void {
  const v = this.form.getRawValue();
  this.store.setFilters({ q: v.q.trim(), category: v.category.trim(), inStock: v.inStock });
}
```

`form.valueChanges` is an Observable that emits on every keystroke. `debounceTime(300)` waits for 300 ms of silence, so typing "gloves" triggers one request, not six. `takeUntilDestroyed()` completes the stream when the component is destroyed: called with no argument inside an injection context (a constructor or field initializer) it finds the component's `DestroyRef` itself. Without it, the subscription would keep the component alive after navigation, which is the textbook RxJS memory leak. The same line appears in the fulfillment, inventory, notifications and audit screens (a search for `takeUntilDestroyed` finds it in the list store and in most filter screens).

Beginner rules to say aloud: nothing happens until `subscribe`; HTTP observables from `HttpClient` emit once and complete, so they do not leak by themselves, but long-lived ones (`valueChanges`, `router.events`, `interval`, `timer`) must be torn down; and the `async` pipe or `toSignal` unsubscribes for you.

## Intermediate tier

### Example 1: `switchMap` for "latest request wins" in a generic store

`frontend/oms-ui/src/app/core/state/list-store.ts` is the project's answer to Redux slices plus a request effect (its own doc comment says so). The core is a stream of reload signals mapped to HTTP calls:

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

Walk through it. `reload$` is a `Subject<void>`; `refresh()` calls `reload$.next()`. `startWith(undefined)` triggers the first load. `switchMap` subscribes to the new HTTP call and unsubscribes from the previous one if it is still running, which cancels the old request and, more importantly, guarantees a slow response to an old query can never overwrite the result of a newer query. `list-store.spec.ts` proves it: the test "ignores a stale response that arrives after a newer query (switchMap)" fires `setFilters` twice, lets the second resolve, then emits a late value on the first and asserts `STALE` never appears. The `catchError` is placed inside the `switchMap` projection, not after it. That placement is the single most important RxJS error-handling idea: an error that reaches the outer pipeline terminates it, and the screen would stop reacting to filters forever; an error caught inside the inner observable is converted into a value (`listFailed`) and the outer stream survives.

The state updates are pure functions ("reducers"): `listLoading`, `listLoaded`, `listFailed`, `withFilters`, `withPage`, `withSort`, all exported and tested without TestBed. `listFailed(s, error)` keeps `s.data` so the table can keep showing the old page under an error banner. Selectors are `computed`:

```ts
readonly items = computed(() => this._state().data?.content ?? []);
readonly total = computed(() => this._state().data?.totalElements ?? 0);
readonly loading = computed(() => this._state().status === 'loading');
readonly empty = computed(() => this._state().status === 'loaded' && this.items().length === 0);
```

Compare the flatMapping operators, because interviewers love this question. The repo uses `switchMap` where only the latest matters. These are the others, with where each belongs (the code below is illustrative, not from the repo):

```ts
// exhaustMap: ignore clicks while a submit is in flight
submit$.pipe(exhaustMap(() => api.create(body, key)))
// concatMap: preserve order, one at a time (e.g. queued stock adjustments)
adjustments$.pipe(concatMap((a) => api.adjustStock(a.id, a.delta, a.reason)))
// mergeMap: run in parallel, results in completion order (bounded by the second argument)
ids$.pipe(mergeMap((id) => api.get(id), 4))
```

The decision table: search-as-you-type and route-param changes want `switchMap`; submit buttons want `exhaustMap`; ordered writes want `concatMap`; independent parallel reads want `mergeMap` with a concurrency cap. The repo's order form handles double-submit differently, with a signal guard (`if (this.submitting()) return;` in `OrderCreate.submit`) plus an `Idempotency-Key`, which is stronger because it also protects against retries after a network failure that no UI operator can see. Never use `switchMap` for a write: cancelling the HTTP observable cancels the browser's request but not the server's work.

### Example 2: parallel loading with `forkJoin`, and isolating failures

`frontend/oms-ui/src/app/features/dashboard/dashboard.ts` loads five independent data sets at once and tolerates partial failure:

```ts
const safe = <T>(o: Observable<T>): Observable<T | null> => o.pipe(catchError(() => of(null)));

forkJoin({
  orders: safe(this.orders.list({ page: 0, size: 100, sort: 'createdAt,desc' })),
  products: safe(this.inventory.products({ page: 0, size: 1, inStock: true })),
  low: staff ? safe(this.inventory.inventory({ page: 0, size: 1, lowStock: true })) : of(null),
  failed: staff ? safe(this.orders.failed({ page: 0, size: 1 })) : of(null),
  health: staff ? safe(this.ops.health()) : of(null),
}).subscribe((r) => {
  this.data.set({ /* ... maps r into one DashboardData value ... */ });
  this.loading.set(false);
});
```

`forkJoin` waits for every source to complete and emits one object with the last value of each; if any source errors and is not handled, the whole join errors and you lose the others. Wrapping each source in `safe(...)` turns an error into `null`, so the dashboard shows "n/a" for the failed card instead of an empty page; this matters in a platform whose services can be independently down. It also works for HTTP because each observable emits once and completes (a `forkJoin` over a stream that never completes never emits). `OrderDetailStore.fetch` uses the same trick for the staff-only saga call: `this.api.saga(id).pipe(catchError(() => of(null)))`. A customer is never asked for the saga, and a missing saga does not fail the page.

Error-handling vocabulary: `catchError` (replace or rethrow), `retry` (resubscribe; RxJS 7 takes `{ count, delay }`, used by `retryInterceptor` in `core/http/interceptors.ts` with `timer(200 * 2 ** (attempt - 1))` for exponential backoff), `throwError(() => err)` (the factory form is the RxJS 7 way), and `finalize` for cleanup. Keep a clear separation: interceptors normalize errors into `ApiError`; stores decide what a failure looks like in state; templates render `ErrorBanner`.

## Advanced tier

### Example 1: polling with `defer`, `expand`, `timer` and `takeUntil` (`core/state/polling.ts`)

Order status is eventually consistent (the saga runs after the 202 response), so the detail page polls with backoff until the order is terminal. The whole utility, `frontend/oms-ui/src/app/core/state/polling.ts`:

```ts
export function backoffDelay(index: number, opts: PollOptions): number {
  return Math.min(opts.maxMs, Math.round(opts.initialMs * (opts.factor ?? 1.5) ** index));
}

export function pollUntil<T>(
  fetchFn: () => Observable<T>,
  isDone: (value: T) => boolean,
  opts: PollOptions,
  stop$: Observable<unknown>,
): Observable<T> {
  return defer(fetchFn).pipe(
    expand((value, index) =>
      isDone(value) ? EMPTY : timer(backoffDelay(index, opts)).pipe(switchMap(() => fetchFn())),
    ),
    takeUntil(stop$),
  );
}
```

Read it operator by operator. `defer(fetchFn)` calls `fetchFn` at subscription time, so every subscription gets a fresh request (a bare observable captured earlier would be cold but pre-built). `expand` is recursion for streams: it emits each source value, and also feeds each value to the projection, whose result is subscribed and whose values are fed back in again. Here the projection returns `EMPTY` when `isDone(value)` is true, which ends the recursion and completes the stream after the terminal value has been emitted; otherwise it waits `backoffDelay(index, opts)` ms with `timer` and then fetches again via `switchMap`. The `index` argument of `expand`'s projection is the number of values emitted so far, so the delay grows geometrically (1,500 ms default, factor 1.5, capped at 8,000 ms by `pollMaxIntervalMs`). `takeUntil(stop$)` is the last operator on purpose: placed last, it also cancels any pending `timer` and in-flight HTTP request inside `expand`; placed earlier, a leaking inner subscription could survive. An error from `fetchFn` is not caught, so the stream errors and stops, and the caller shows a manual Refresh button (`docs/architecture/angular-architecture.md` section 6 states this choice).

The consumer, `frontend/oms-ui/src/app/features/orders/order-detail.store.ts`, adds the signal side:

```ts
pollUntil(
  () => this.fetch(id),
  (s) => isTerminal(s.order),
  { initialMs: pollIntervalMs, maxMs: pollMaxIntervalMs },
  this.stop$,
).subscribe({
  next: (s) => {
    this._snapshot.set(s);
    this._loading.set(false);
    if (isTerminal(s.order)) this._polling.set(false);
  },
  error: (e: unknown) => {
    this._error.set(normalizeError(e));
    this._loading.set(false);
    this._polling.set(false);
  },
  complete: () => {
    this._loading.set(false);
    this._polling.set(false);
  },
});
```

The store is `@Injectable()` provided at component level (`providers: [OrderDetailStore]`), keeps a `Subject<void>` named `stop$`, and calls `stop$.next()` from `stopPolling()`, which `start()` calls first (so navigating to another order never leaves two pollers running) and which a `destroyRef.onDestroy` hook calls when the page goes away. The pattern, "RxJS produces events, `.subscribe` writes signals, the template reads signals", is the repo's default. Testing is plain virtual time: `polling.spec.ts` uses `fakeAsync` and `tick(100)`, `tick(199)`, `tick(1)` to assert the exact schedule, that no polling happens after the terminal value, that `stop$` halts it, and that errors propagate. A flaw worth naming: `forkJoin` of order, history and saga re-fetches everything every cycle, which is simple but chatty; a conditional re-fetch of history only when the status changed would cut load, and server-sent events or WebSockets would remove polling entirely (not implemented; the doc lists them as a possible upgrade).

### Example 2: `effect`, `untracked`, `toSignal`, and the RxJS-signal boundary

`OrderDetail` (`features/orders/order-detail.ts`) restarts polling when the route id changes, and the code comment calls it "useEffect-like":

```ts
readonly id = input.required<string>();

constructor() {
  // useEffect-like: restart when the route id changes (untracked: only `id` is a dependency)
  effect(() => {
    const id = this.id();
    untracked(() => this.store.start(id));
  });
}
```

`effect(fn)` runs `fn` once and again whenever any signal read synchronously inside it changes. Dependencies are tracked automatically from what is read; there is no dependency array to forget. The `untracked` wrapper is essential here: `store.start(id)` reads and writes several signals (`_loading.set(true)` and so on), and without `untracked` the effect could subscribe to signals it does not mean to depend on and re-run unexpectedly. Rules of thumb for `effect` in Angular 20 (stable API): use it for side effects that must follow state (logging, syncing to storage or a non-Angular library, starting IO), never to derive state (use `computed`, or `linkedSignal` for writable derived state), keep signal writes inside it rare and deliberate, and remember it is scheduled, not synchronous: it runs during change detection, not at the moment of the write. It is created in an injection context (constructor or field) and is destroyed with it; use `onCleanup` inside the effect function for teardown of per-run resources.

`toSignal` converts the other direction. `frontend/oms-ui/src/app/app.ts`:

```ts
protected readonly isNarrow = toSignal(this.bp.observe('(max-width: 900px)').pipe(map((s) => s.matches)), { initialValue: false });
protected readonly nav = computed(() => visibleNav(NAV_ITEMS, this.auth.roles()));
```

`toSignal` subscribes immediately, keeps the latest value, unsubscribes when the injection context is destroyed, and needs an `initialValue` (or `requireSync: true`) because a signal must always hold a value; without it the type includes `undefined`. `OrderCreate` uses it for the form: `private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null })`, then reads `this.formValue()` inside a `computed` so the order total recomputes when any field changes. The opposite helper `toObservable(signal)` exists in `@angular/core/rxjs-interop`; it emits asynchronously through an effect, so it does not replace synchronous reads.

When to pick which, as a decision rule you can say in one breath: **time, cancellation, retry, debounce or combining event streams: RxJS. Current value that a template or another computation reads: signal. Crossing the boundary: `toSignal` for observable to signal; `.subscribe` or `toObservable` for the reverse; clean up with `takeUntilDestroyed`.** Signals do not replace RxJS for HTTP in Angular 20; `resource`, `rxResource` and `httpResource` (signal-based async loaders) are experimental in Angular 20 and are not used in this repository.

### Example 3: sharing and multicasting, and the cold-observable trap

A cold Observable re-runs its work for every subscriber. If a template used `{{ (orders$ | async)?.length }}` twice with an `HttpClient` observable, the request would be sent twice. The repo largely avoids the trap by materializing results into signals once (`this.data.set(...)`), but you should know the operators: `share()` (multicast with refcount), `shareReplay({ bufferSize: 1, refCount: true })` (multicast and replay the latest value to late subscribers; the `refCount: true` option is needed to unsubscribe from the source when the last subscriber leaves, otherwise `shareReplay(1)` keeps the source subscribed forever), `BehaviorSubject` (a subject with a current value, the pre-signal way to build a store), and `firstValueFrom` / `lastValueFrom` (the promise bridge, replacing the deprecated `toPromise`; `firstValueFrom(ref.afterClosed())` appears in `stock-adjust-dialog.spec.ts`). A custom `new Observable(subscriber => ...)` with a teardown function is rarely needed; the one in the repo, `OrderApi.create` in `core/api/apis.ts`, wraps `http.post(..., { observe: 'response' })` to expose the `Idempotent-Replay` and `Location` headers and returns `() => sub.unsubscribe()` so cancellation propagates. A `map` on the response would have been shorter; mention that as an honest simplification opportunity.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Auth signal store | `frontend/oms-ui/src/app/core/auth/auth.store.ts` | private writable signal, `asReadonly`, `computed` roles, expiry timer |
| Cart signal store | `frontend/oms-ui/src/app/core/state/cart.store.ts` | pure update functions plus `signal.update` |
| Generic list store | `frontend/oms-ui/src/app/core/state/list-store.ts` | `switchMap` + inner `catchError`, pure reducers, `computed` selectors |
| Polling utility | `frontend/oms-ui/src/app/core/state/polling.ts` | `defer`, `expand`, `timer`, `switchMap`, `takeUntil` |
| Per-screen store | `frontend/oms-ui/src/app/features/orders/order-detail.store.ts` | `forkJoin`, polling, `Subject` stop signal, component-scoped provider |
| `effect` + `untracked` | `frontend/oms-ui/src/app/features/orders/order-detail.ts` | restart polling on route id change |
| `toSignal` | `frontend/oms-ui/src/app/app.ts`, `features/orders/order-create.ts` | breakpoint observer, form value changes |
| Debounced filters | `features/catalog/catalog.ts` and four other screens | `debounceTime(300)` + `takeUntilDestroyed()` |
| Polling dashboard | `frontend/oms-ui/src/app/features/ops/ops-dashboard.ts` | `merge(timer, Subject)` + `switchMap` + `forkJoin` + per-service `catchError` |
| Retry/backoff | `frontend/oms-ui/src/app/core/http/interceptors.ts` | `retry({ count, delay })` for GET only |
| Tests | `core/state/polling.spec.ts`, `core/state/list-store.spec.ts` | `fakeAsync`/`tick`, stale-response test |

The ops dashboard is a compact showcase of operator composition in one place (`features/ops/ops-dashboard.ts`):

```ts
merge(timer(0, REFRESH_MS).pipe(filter(() => this.auto())), this.manual$)
  .pipe(
    switchMap(() =>
      forkJoin({
        cards: forkJoin(
          SERVICES.map((service) =>
            this.api.messaging(service).pipe(
              map((data): ServiceCard => ({ service, data, error: null })),
              catchError((e: unknown) => of<ServiceCard>({ service, data: null, error: normalizeError(e) })),
            ),
          ),
        ),
        // ... health call with its own catchError ...
      }),
    ),
    takeUntilDestroyed(),
  )
  .subscribe(({ cards, health }) => {
    this.cards.set(cards);
    // ...
  });
```

Two ticks (`timer(0, REFRESH_MS)` and the manual `Subject`) are merged; the `auto` signal is read inside `filter`, so the toggle takes effect without resubscribing; `switchMap` makes a manual refresh cancel an in-flight automatic one; a failing service becomes a card with an error, not a dead dashboard. Note the weakness: reading a signal inside `filter` is an untracked read in a callback, which is correct here (it is not in a reactive context), but it is a pattern to keep deliberate.

### Demonstration-only vs production-grade

Production-grade: encapsulated signal stores, pure and tested reducers, cancellation-safe loading with `switchMap`, subscription teardown on every long-lived stream, bounded retry only for idempotent reads, partial-failure tolerance, virtual-time tests for polling.

Honest limits: no NgRx or SignalStore (by decision, with explicit revisit triggers in ADR-0012: several stores writing the same entity, devtools or time travel, offline queues); no devtools or action log; no request deduplication or client-side cache (server data is refetched, not cached); polling instead of SSE or WebSocket; each poll re-fetches order, history and saga together; error handling in screens is mostly `.subscribe({ next, error })` with signals rather than a uniform pattern; some one-shot `.subscribe` calls in screens (for example `OrderCreate.ngOnInit`) are not wrapped in `takeUntilDestroyed`, which is harmless for completing HTTP calls but is a convention gap; experimental `resource` APIs are not used.

## Common mistakes and gotchas

1. **Forgetting to unsubscribe from long-lived streams.** Use `takeUntilDestroyed`, `toSignal` or the `async` pipe. HTTP completes by itself; `valueChanges`, `router.events` and timers do not.
2. **Nested subscribes.** Subscribing inside a subscribe loses cancellation and ordering. Flatten with `switchMap`, `concatMap`, `exhaustMap` or `mergeMap`.
3. **Putting `catchError` after `switchMap`.** One failure kills the outer stream and the screen. Catch inside the inner observable.
4. **Using `switchMap` for writes.** It cancels the client request, not the server work, and can drop user actions. Use `concatMap` or `exhaustMap` with idempotency keys.
5. **`forkJoin` with a source that never completes, or with one unhandled error.** It never emits or loses everything. Wrap sources (`safe(...)`) and use `combineLatest` for ongoing streams.
6. **`takeUntil` not last.** Operators after it can leak inner subscriptions. Put it at the end of the pipe.
7. **Writing signals inside `computed`.** `computed` must be pure and synchronous; write in `effect`, event handlers or subscribe callbacks.
8. **Using `effect` to derive state.** It causes an extra change detection cycle and ordering bugs; use `computed` or `linkedSignal`.
9. **Reading signals inside `effect` that you did not mean to track.** Wrap calls in `untracked`, as `OrderDetail` does.
10. **Mutating the object inside a signal.** `signal.update(x => { x.items.push(i); return x; })` returns the same reference and may not notify; return a new array or object.
11. **Calling `toSignal` outside an injection context.** It throws; pass an `injector` option or create it in a field initializer.
12. **`shareReplay(1)` without `refCount: true`.** The source subscription never closes.
13. **Assuming `debounceTime` emits the first value immediately.** It does not; use `throttleTime` or `auditTime` for the leading edge.
14. **Treating experimental APIs as stable.** `resource`, `rxResource` and `httpResource` are experimental in Angular 20.

## Quick-recall cheat sheet

* Observable = lazy push stream; `subscribe` starts it; `complete` or `error` ends it; operators transform it.
* Flattening: `switchMap` latest wins (search, route params, list reloads); `concatMap` ordered queue; `mergeMap` parallel with limit; `exhaustMap` ignore while busy (submit).
* Errors: `catchError` inside the inner stream, `retry({ count, delay })`, `throwError(() => err)`; retry only idempotent reads.
* Teardown: `takeUntilDestroyed()` (injection context) or `takeUntilDestroyed(destroyRef)`; `takeUntil(stop$)` last; `async` pipe; `toSignal`.
* `pollUntil(fetch, isDone, {initialMs, maxMs, factor}, stop$)` = `defer` + `expand` + `timer` + `switchMap` + `takeUntil`; emits the terminal value, then completes; errors end the stream.
* Signals: `signal()`, `.set/.update`, `asReadonly()`, `computed()` (lazy, memoized, pure), `effect()` (side effects, scheduled), `untracked()`, `linkedSignal()` (writable derived), `toSignal`/`toObservable` bridge.
* Division of labour here: RxJS for IO and time, signals for state, `.subscribe` writes into a signal, templates read signals.
* Reducers as pure functions: `listLoading`, `listLoaded`, `listFailed`, `withFilters`, `withPage`, `withSort`; selectors as `computed`.
* `shareReplay({ bufferSize: 1, refCount: true })`; `firstValueFrom`/`lastValueFrom` replace `toPromise`.
* Experimental in Angular 20: `resource`, `rxResource`, `httpResource` (not used here).
* ADR-0012 revisit triggers: more stores writing one entity, devtools or time travel, offline write queue, then NgRx SignalStore first.

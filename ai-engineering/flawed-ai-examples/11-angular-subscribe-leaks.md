# 11 Angular subscription leaks and runaway polling

Real counterparts: `frontend/oms-ui/src/app/core/state/polling.ts` (`pollUntil`, `backoffDelay`),
`frontend/oms-ui/src/app/features/orders/order-detail.store.ts`, spec `core/state/polling.spec.ts`.

## Prompt that produces it
"Show the order status and refresh it every 2 seconds until it is finished."

## Flawed code
```ts
@Component({ selector: 'app-order-detail', template: `{{ order?.status }}` })
export class OrderDetailComponent implements OnInit {
  order?: Order;
  constructor(private api: OrdersApi, private route: ActivatedRoute) {}

  ngOnInit() {
    this.route.paramMap.subscribe(p => {                 // never unsubscribed
      const id = p.get('id')!;
      setInterval(() => {                                // never cleared, one per param emission
        this.api.get(id).subscribe(o => this.order = o); // nested subscribe, no error handling
      }, 2000);
    });
  }
}
```

## Why it is risky here
* Navigating away does not stop the timer: every visit adds another interval that keeps calling the gateway, so a user
  who browses ten orders runs ten parallel 2-second polls. With the gateway rate limiter (429) this turns into a
  self-inflicted DoS for that user, and it hammers order-service.
* A fixed 2s interval ignores terminal states (`FULFILLED`, `REJECTED`, `CANCELLED`, `FAILED`) and never backs off
  while the saga is slow.
* Nested `subscribe` loses ordering and cancellation: a slow response for an old order can overwrite a newer one.
* No error path: after a 429 or 503 the UI silently shows stale status as if it were current.

## Corrected code
```ts
// poll with backoff, stop on terminal state, stop on destroy; switchMap cancels stale in-flight requests
const stop$ = new Subject<void>();                       // or takeUntilDestroyed(destroyRef)
pollUntil(() => this.api.get(id), o => TERMINAL.has(o.status),
          { initialMs: cfg.pollIntervalMs, maxMs: cfg.pollMaxIntervalMs }, stop$)
  .subscribe({ next: o => this.order.set(o), error: e => this.error.set(toApiError(e)) });
```
Prefer the `async` pipe or signals (`toSignal`) so Angular owns the subscription lifetime; use
`takeUntilDestroyed()` for imperative subscriptions; never `setInterval` + `subscribe`.

## Regression tests (illustrative, not executed here)
```ts
it('stops polling when the stop$ emits', fakeAsync(() => {
  const calls = jasmine.createSpy().and.returnValue(of({ status: 'CREATED' }));
  const stop$ = new Subject<void>();
  pollUntil(calls, o => o.status === 'FULFILLED', { initialMs: 100, maxMs: 400 }, stop$).subscribe();
  tick(250); const seen = calls.calls.count();
  stop$.next(); tick(5000);
  expect(calls.calls.count()).toBe(seen);                // no further requests after teardown
}));
it('completes on a terminal state', fakeAsync(() => { /* isDone true -> stream completes, no timers left */ }));
```
The runnable equivalents are in `frontend/oms-ui/src/app/core/state/polling.spec.ts` (verify with `ng test`; whether they
were executed on this machine is recorded in `docs/governance/test-strategy.md`).

## Review prompt to catch it
"Who unsubscribes? What stops the timer when the component is destroyed? What happens on a terminal state and on a 429?"

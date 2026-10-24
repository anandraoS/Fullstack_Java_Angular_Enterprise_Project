# Angular 20 Architecture and TypeScript: Standalone Components, DI, Routing, Interceptors, Forms, Testing and Strict Types

This chapter covers the framework and language half of the front-end interview. The UI lives in `frontend/oms-ui`: Angular 20 (`@angular/core` 20.3.x in `package.json`), Angular Material 20.2, RxJS 7.8, TypeScript 5.9 in strict mode, and zone.js 0.15 for change detection. It has about a dozen screens (orders, catalog, inventory, fulfillment, notifications, ops, audit), no NgModules, and no NgRx. State management with signals and RxJS gets its own chapter (`09-rxjs-and-signals.md`); this chapter is about the structure around it.

## Big picture and kid-friendly analogy

Picture a big toy shop run by a very organised family.

* **A standalone component** is one Lego set in its own box. The box lists exactly which other bricks it needs (its `imports`), so you can pick up one box and build it anywhere. The old way (NgModules) was a giant shared bucket of bricks where nobody knew which set needed which brick.
* **Lazy loading** is a toy shop that keeps most boxes in the back room. When a customer asks for the "Inventory" set, someone fetches that one box. The shop window (the first page load) stays light.
* **Dependency injection** is the shop's stockroom clerk. You do not go and make your own scissors; you hold up a card that says "scissors" (`inject(HttpClient)`) and the clerk hands you the one shared pair, or a private pair if your table has its own supply (component-level `providers`).
* **An HTTP interceptor** is the checkout lane's helper who stamps every parcel that leaves the shop with a sticker (correlation id), adds the loyalty badge (bearer token), tries again if the delivery van had a flat tyre, and translates a messy "van exploded" report into a polite note for the customer.
* **A route guard** is the sign on the staff-only door that says "Staff only". It is polite and helps honest people avoid wandering in; it is not a lock. The real lock is on the warehouse (the backend).
* **Change detection** is the shop assistant who walks round checking which price tags need updating. With signals, the shelves ring a little bell when a price changes, so the assistant only visits that shelf.
* **TypeScript** is the label maker. It does not change the toys, but it stops you putting a "teddy bear" label on a box of "knives" before the box leaves the shop.

| Analogy | Angular or TypeScript concept |
|---|---|
| Lego set with its own parts list | Standalone component with `imports` |
| Toys fetched from the back room | `loadComponent: () => import(...)` lazy route |
| Stockroom clerk | Injector, `inject()`, `providedIn: 'root'`, `providers` |
| Stamping parcels at checkout | Functional `HttpInterceptorFn` chain |
| "Staff only" sign | Functional `CanActivateFn` guard (UX, not security) |
| Shelf that rings a bell | Signal-based change notification |
| Label maker | Static types, strict mode |

## Beginner tier

### Example 1: a standalone component with a signal input and new control flow

`frontend/oms-ui/src/app/shared/components/status-chip.ts` is a complete presentational component in 15 meaningful lines. It declares no module, its template uses interpolation, and its only input is a required signal input:

```ts
@Component({
  selector: 'app-status-chip',
  template: `<span class="chip" [class]="'chip tone-' + tone()"
    ><span aria-hidden="true" class="glyph">{{ glyph() }}</span> {{ label() }}</span
  >`,
  // ... styles ...
})
export class StatusChip {
  readonly status = input.required<string>();
  readonly tone = computed(() => toneFor(this.status()));
  readonly glyph = computed(() => GLYPH[this.tone()]);
  readonly label = computed(() => this.status().replace(/_/g, ' '));
}
```

What to explain: `input.required<string>()` is the signal-input API (stable since Angular 19). Reading it is a function call (`this.status()`), a parent that forgets to bind it fails at compile time under `strictTemplates`, and `computed()` derives values that update only when `status` changes. Compare with the older `@Input() status!: string;` where the `!` is you promising the compiler something it cannot check. The component also illustrates an accessibility rule from the repo: the glyph plus text means status is never conveyed by colour alone.

The same chapter of the repo shows the new control flow in `frontend/oms-ui/src/app/app.html`:

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

`@if`, `@else`, `@for` and `@switch` are built into the template compiler (introduced in Angular 17, stable since Angular 18), need no `CommonModule` import, and replace `*ngIf` and `*ngFor`. `track` is mandatory in `@for` and plays the role of `trackBy`: give Angular a stable identity (`item.path`, `o.id`) so it reuses DOM rows instead of recreating them. A grep over `src/app` finds no `*ngIf`, `*ngFor` or `NgModule`, so the project uses only the modern syntax. Note also the file naming: Angular 20's updated style guide drops the `.component` suffix, so the class `App` lives in `app.ts` and `StatusChip` in `status-chip.ts`.

### Example 2: dependency injection with `inject()`, root singletons and component-scoped providers

Services are classes registered with the injector. `frontend/oms-ui/src/app/core/state/cart.store.ts` is the smallest example:

```ts
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly _lines = signal<CartLine[]>([]);
  readonly lines = this._lines.asReadonly();
  readonly count = computed(() => this._lines().reduce((n, l) => n + l.quantity, 0));
  readonly total = computed(() => this._lines().reduce((n, l) => n + l.quantity * l.unitPrice, 0));
  // ...
}
```

`providedIn: 'root'` makes one instance for the whole app, created lazily on first injection and tree-shaken away if never used. Consumers ask for it with the `inject()` function in a field initializer, for example in `Catalog` (`features/catalog/catalog.ts`): `protected readonly cart = inject(CartStore);`. `inject()` works only in an injection context (a constructor, a field initializer, a factory, a functional guard or interceptor), which is why the repo can use it in plain functions such as guards. Constructor injection still works; field `inject()` is shorter, works with inheritance (`BaseApi` in `core/api/apis.ts` injects `HttpClient` once for every API class) and avoids repeating constructor parameters in subclasses.

Scope matters. `OrderDetail` (`features/orders/order-detail.ts`) lists `providers: [OrderDetailStore]` in its `@Component` decorator, and `OrderDetailStore` is a plain `@Injectable()` with no `providedIn`. That gives each order-detail page its own store instance, created when the component is created and destroyed with it (the store registers `destroyRef.onDestroy(() => this.stopPolling())`). Two browser tabs or two navigations never share polling state. In interviews, contrast the three lifetimes: root (app-wide singleton), route or component `providers` (per instance), and `providedIn: 'platform'` or environment providers in `app.config.ts` (the HTTP client, router, initializers).

The application's root providers are in `frontend/oms-ui/src/app/app.config.ts`:

```ts
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    // Order matters: request flows left->right, errors flow right->left. retry sees normalized ApiErrors.
    provideHttpClient(withInterceptors([correlationInterceptor, authInterceptor, retryInterceptor, errorInterceptor])),
    provideAppInitializer(() => inject(ConfigService).load()),
  ],
};
```

`main.ts` is one line, `bootstrapApplication(App, appConfig)`. Standalone applications have no `AppModule`: features are switched on by `provideXxx()` functions, which is also how Angular's own libraries are configured now.

## Intermediate tier

### Example 1: lazy routes, functional guards and route-parameter inputs

`frontend/oms-ui/src/app/app.routes.ts` lazy-loads every feature with `loadComponent` and attaches guards:

```ts
{
  path: 'inventory',
  canActivate: [roleGuard],
  data: { roles: STAFF },
  title: 'Inventory',
  loadComponent: () => import('./features/inventory/inventory').then((m) => m.Inventory),
},
{
  path: 'orders/:id',
  canActivate: [authGuard],
  title: 'Order detail',
  loadComponent: () => import('./features/orders/order-detail').then((m) => m.OrderDetail),
},
```

Each dynamic `import()` becomes its own JavaScript chunk, fetched when the user first navigates there, so the initial bundle holds only bootstrap, login and shared code (the production budget in `angular.json` warns at 900 kB and errors at 1.5 MB for the initial bundle). `withComponentInputBinding()` in `provideRouter` is why `OrderDetail` can declare `readonly id = input.required<string>();` and receive `:id` straight from the URL, with no `ActivatedRoute` subscription. Query parameters bind the same way: `Login` declares `returnUrl = input<string>()` and `reason = input<string>()`.

The guards are functions, not classes (`frontend/oms-ui/src/app/core/auth/guards.ts`):

```ts
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  if (!auth.isAuthenticated()) return router.createUrlTree(['/login']);
  const wanted = route.data['roles'] as Role[] | undefined;
  return auth.hasAnyRole(wanted) ? true : router.createUrlTree(['/forbidden']);
};
```

Returning a `UrlTree` (rather than calling `router.navigate` and returning `false`) is the recommended way to redirect from a guard because the router cancels the current navigation and starts the new one atomically. Class-based guards (`implements CanActivate`) are deprecated in favour of `CanActivateFn`. The repo is explicit that guards are UX only, in the file's own doc comment and in `docs/architecture/angular-architecture.md` section 5: every endpoint re-checks roles and ownership on the server, and a user can edit client code or call the API with curl. If an interviewer asks "how do you secure an Angular route", the answer is "I do not; I hide it for convenience and enforce on the server".

Note `route.data['roles']` with bracket access. The strict compiler flag `noPropertyAccessFromIndexSignature` (in `tsconfig.json`) forbids `route.data.roles` because `data` is an index-signature type, which is a small but real example of strictness changing code style.

### Example 2: functional HTTP interceptors and ordering

Interceptors in `frontend/oms-ui/src/app/core/http/interceptors.ts` are plain functions registered in `provideHttpClient(withInterceptors([...]))`. The token interceptor shows the key safety rule (never leak the bearer token to other origins or to the login call):

```ts
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const apiBase = inject(ConfigService).apiBaseUrl;
  const token = inject(AuthStore).token();
  if (!token || !isApiCall(req.url, apiBase) || isTokenCall(req.url)) return next(req);
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
```

`HttpRequest` is immutable, so `req.clone()` is the only way to change it. The retry interceptor uses RxJS 7's `retry` configuration object, and it only touches idempotent methods:

```ts
export const retryInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') return next(req);
  return next(req).pipe(
    retry({
      count: MAX_GET_RETRIES,
      delay: (err, attempt) => (isTransient(err) ? timer(200 * 2 ** (attempt - 1)) : throwError(() => err)),
    }),
  );
};
```

Ordering: the array is `[correlationInterceptor, authInterceptor, retryInterceptor, errorInterceptor]`. A request passes through them left to right, and a response or error passes back right to left. `errorInterceptor` sits last, nearest the network, so it turns raw `HttpErrorResponse`s into the project's `ApiError` first; `retryInterceptor` then sees the normalized error and can decide with `isTransient` (status 0, 502, 503 or 504). If you put retry before error normalization in the array, the types it sees change; that is the kind of detail interviewers probe. `docs/architecture/angular-architecture.md` section 4 lists what each one does, and the retry rule connects to the backend contract: mutations are never auto-retried; order creation is protected by an `Idempotency-Key` header instead.

The initializer `provideAppInitializer(() => inject(ConfigService).load())` blocks bootstrap until `config.json` has been fetched, so one build works in every environment (the nginx image generates the file from environment variables, see `frontend/oms-ui/nginx.conf`). That replaces build-time `environment.ts` files and keeps secrets out of the bundle: the file only holds `apiBaseUrl` and polling intervals.

### Example 3: typed reactive forms with custom validators

The repo uses reactive forms exclusively. `Login` builds a typed group with `inject(FormBuilder).nonNullable`:

```ts
private readonly fb = inject(FormBuilder).nonNullable;
// ...
protected readonly form = this.fb.group({
  username: ['', [Validators.required]],
  password: ['', [Validators.required]],
});
```

`nonNullable` makes controls typed as `FormControl<string>` instead of `FormControl<string | null>`, and `getRawValue()` returns `{ username: string; password: string }`. Custom validators are plain functions in `features/orders/order-form.ts`, tested without TestBed:

```ts
export function postalCode(c: AbstractControl): ValidationErrors | null {
  const v = (c.value ?? '') as string;
  return v === '' || POSTAL.test(v.trim()) ? null : { postalCode: true };
}

export function itemCount(min = 1, max = MAX_ITEMS): ValidatorFn {
  return (c) => {
    const n = (c as FormArray).length;
    if (n < min) return { minItems: { min } };
    if (n > max) return { maxItems: { max } };
    return null;
  };
}
```

A validator returns `null` for valid and an error object for invalid; a factory like `itemCount` returns a `ValidatorFn`. `OrderCreate` composes them on a `FormArray` (`this.fb.array([this.newRow()], [itemCount(), uniqueProducts])`) and also maps server-side field errors back onto controls with `applyServerErrors`, which turns the backend's RFC 7807 `errors[]` (`shippingAddress.city`, `items[0].quantity`) into control errors via `form.get(path)`. Empty values pass the custom validators on purpose (compose with `Validators.required`), which is the Angular convention. The client validates for fast feedback only; the backend contract (`1-50 lines`, `quantity 1-1000`, no duplicate product) is authoritative.

## Advanced tier

### Example 1: change detection: what the repo does, and what it does not do

Honest answer first: this project does not use `ChangeDetectionStrategy.OnPush` anywhere (a grep for `OnPush` over `src/app` returns nothing), and it runs with zone.js (`provideZoneChangeDetection({ eventCoalescing: true })`). `docs/architecture/angular-architecture.md` section 2 states that the app is zone-based and that signals "make a future zoneless switch feasible". Explain the mechanics so you can reason about it:

* With zone.js, Angular runs a change detection pass over the component tree after every async event it patched (clicks, timers, XHR, promise callbacks). `eventCoalescing: true` batches several events in the same tick into one pass.
* `OnPush` marks a component as checked only when an input reference changes, an event originates inside it, an `async` pipe or signal it reads notifies, or `markForCheck()` is called. It makes pure presentational components cheap.
* Signals change the model. A template that reads `store.items()` registers a reactive dependency; when that signal changes, Angular marks exactly that view for refresh. In Angular 20 signals work in default-strategy and `OnPush` components alike, and the framework supports zoneless apps (`provideZonelessChangeDetection()`, promoted to stable in Angular 20.2; confirm against the exact version you ship) where only signals, events and `markForCheck` schedule a pass.

Because every component in this repo reads signals (`computed` values, `input()`), adding `changeDetection: ChangeDetectionStrategy.OnPush` to the shared components such as `StatusChip` and `DataTable` would be a cheap, low-risk change, and removing zone.js would be the next step; the repo has not done either (check each screen for plain mutable fields before switching). One genuine zone dependency exists to mention: `OrderCreate.addRow` and `removeRow` use `setTimeout` to move focus after the DOM updates, which behaves the same under zoneless but is the kind of code to re-test.

A second advanced topic is the template function-call trap. The templates call methods such as `shortId()` and `lineTotal(i)` and `OrderCreate.total` is a `computed` that reads `this.formValue()` (a `toSignal` of `form.valueChanges`) to force recomputation when the form changes. Calling a plain method from a template re-runs it on every change detection pass; that is fine when cheap, and the repo uses `computed` for anything non-trivial (`StatusChip.tone`). The rule of thumb: pure and cheap in templates, derived state in `computed`, never I/O.

Finally the experimental APIs: `resource()`, `rxResource()` and `httpResource()` (signal-based async data loading introduced in Angular 19 and still marked experimental in Angular 20) are not used here. The repo keeps server data in its own `ListStore` and `OrderDetailStore` with RxJS `switchMap`, which is stable API. If an interviewer asks about `httpResource`, say it is promising for read-only fetches keyed on signals but is experimental and its API may change, so a production code base should isolate it behind a service.

### Example 2: TypeScript generics, narrowing and utility types in real code

Generics: `frontend/oms-ui/src/app/core/state/list-store.ts` defines a reusable store parameterised over item and filter types:

```ts
export class ListStore<T, F extends object> {
  private readonly _query;
  private readonly _state = signal<ListState<T>>(initialListState<T>());
  // ...
  constructor(
    private readonly fetchFn: (q: ListQuery<F>) => Observable<Page<T>>,
    initialFilters: F,
    size = 20,
    sort = '',
  ) {
```

`Catalog` instantiates it as `new ListStore<Product, ProductFilter>(...)`, so `store.items()` is `Product[]` and the filter shape is checked at the call site. `F extends object` is a generic constraint (you cannot pass a string as a filter). `Page<T>` in `core/models.ts` is the contract's pagination envelope as an interface generic, and `pollUntil<T>` in `core/state/polling.ts` keeps the polled value type end to end.

Narrowing: `normalizeError` in `core/http/api-error.ts` takes `unknown` (the correct type for a `catch` variable) and narrows it with `instanceof` and a user-defined type guard:

```ts
function isProblem(body: unknown): body is ProblemDetail {
  return typeof body === 'object' && body !== null;
}
// ...
if (err instanceof HttpErrorResponse) {
  const body = err.error;
  const problem: ProblemDetail = isProblem(body) && !(body instanceof Blob) ? body : {};
```

The predicate `body is ProblemDetail` tells the compiler that, when the function returns true, the argument can be treated as `ProblemDetail`. Be honest in an interview that this guard is permissive (it only checks "is a non-null object"), which is acceptable because every `ProblemDetail` field is optional; a stricter version would validate fields or use a schema library. `parseStoredSession` in `core/auth/auth.store.ts` is the more defensive example: it parses JSON into `Partial<Session>`, then checks `typeof s.accessToken === 'string'`, `Array.isArray(s.roles)` and `typeof s.expiresAt === 'number'` before building a full `Session`. Never trust `JSON.parse(...) as Session`; a cast is a promise, a check is evidence.

Unions and literal types model the domain: `Role = 'CUSTOMER' | 'OPERATOR' | 'ADMIN' | 'SERVICE'` and the nine-value `OrderStatus` mirror the shared contract, so a typo such as `'FULFILED'` fails to compile. `Record<string, Tone>` types the lookup table in `status-chip.ts`, `Pick<Product, 'id' | 'sku' | 'name' | 'price'>` narrows what `CartStore.add` accepts, `Partial<AppConfig>` types the config override, and `ReturnType<typeof setTimeout>` in `AuthStore` avoids the Node-versus-DOM timer type clash. `readonly Role[]` parameters (`hasAnyRole(session, wanted: readonly Role[] | undefined)`) promise not to mutate the caller's array.

Strict mode: `frontend/oms-ui/tsconfig.json` enables `strict` (which turns on `strictNullChecks`, `noImplicitAny`, `strictFunctionTypes` and more), plus `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noImplicitReturns` and `noFallthroughCasesInSwitch`, and Angular's `strictTemplates`, `strictInputAccessModifiers` and `strictInjectionParameters`. `strictTemplates` is the one that catches template errors such as passing a `string | null` to a `string` input. The common escape hatches to avoid: `any`, non-null assertion `!`, and `as unknown as X` casts. The repo has a few (for example the `items` getter in `OrderCreate` uses `as unknown as FormArray<...>` to recover a typed `FormArray`, and `data-table.ts` uses `actions()!`); they are documented trade-offs, not a model to copy.

### Example 3: testing at three levels

The testing approach (`docs/architecture/angular-architecture.md` section 8) is: pure functions without TestBed, services and guards with the injector, and screens with the real interceptor chain against `HttpTestingController`. The tests are Karma and Jasmine (`karma.conf.js`, `ng test`); the Angular team treats Karma as legacy and offers experimental Jest and Web Test Runner builders, so a new project today would evaluate those, but the concepts below are identical.

Pure function test (`core/state/polling.spec.ts`) uses `fakeAsync` and `tick` to control virtual time without real waiting:

```ts
it('stops when stop$ emits (takeUntil)', fakeAsync(() => {
  const stop = new Subject<void>();
  let calls = 0;
  pollUntil(() => of(++calls), () => false, opts, stop).subscribe();
  tick(100);
  expect(calls).toBe(2);
  stop.next();
  tick(10_000);
  expect(calls).toBe(2);
  discardPeriodicTasks();
}));
```

Guard test (`core/auth/guards.spec.ts`) runs a functional guard in an injection context with `TestBed.runInInjectionContext` and asserts the resulting `UrlTree`:

```ts
const run = (guard: typeof authGuard, r: ActivatedRouteSnapshot) => TestBed.runInInjectionContext(() => guard(r, state));
// ...
const result = run(authGuard, route()) as UrlTree;
expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Forders%2Fabc');
```

Interceptor test (`core/http/interceptors.spec.ts`) uses the shared `httpTestProviders()` from `testing/test-utils.ts`, which mirrors the production chain, then `HttpTestingController.expectOne` to inspect the outgoing request and `mock.verify()` in `afterEach` to guarantee no stray requests. Testing the real chain, not mocks of it, is what proves that the token is not attached to `/auth/token` or to foreign origins.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Bootstrap and root providers | `frontend/oms-ui/src/main.ts`, `frontend/oms-ui/src/app/app.config.ts` | `bootstrapApplication`, zone-based change detection, interceptor chain, config initializer |
| Lazy standalone routes | `frontend/oms-ui/src/app/app.routes.ts` | 14 `loadComponent` routes (including `forbidden` and the wildcard), `title`, `data.roles`, wildcard `NotFound` |
| Functional guards | `frontend/oms-ui/src/app/core/auth/guards.ts` | `authGuard`, `roleGuard`, `guestGuard`; return `UrlTree` |
| Interceptors | `frontend/oms-ui/src/app/core/http/interceptors.ts`, `.../api-error.ts` | correlation id, bearer, GET retry, `ApiError` normalization |
| Typed API clients | `frontend/oms-ui/src/app/core/api/apis.ts` | `BaseApi` with `inject(HttpClient)`; `toParams` drops empty values |
| Models mirroring the contract | `frontend/oms-ui/src/app/core/models.ts` | unions, `Page<T>`, `ProblemDetail`; comment says keep in sync with the shared contract |
| Reusable UI | `frontend/oms-ui/src/app/shared/components/*.ts` | `DataTable`, `StatusChip`, `ErrorBanner`; inputs and outputs only, no API calls |
| Forms and validators | `frontend/oms-ui/src/app/features/orders/order-form.ts`, `order-create.ts`, `login/login.ts` | typed reactive forms, `FormArray`, server-error mapping, idempotency key holder |
| Runtime config | `frontend/oms-ui/src/app/core/config.service.ts`, `frontend/oms-ui/public/config.json` | fetched before bootstrap completes |
| Strict TypeScript | `frontend/oms-ui/tsconfig.json` | `strict` plus four extra flags, Angular `strictTemplates` |
| Tests | `*.spec.ts` beside code, `frontend/oms-ui/src/app/testing/test-utils.ts`, `frontend/oms-ui/karma.conf.js` | Karma/Jasmine; pure, injector and HTTP-level tests |
| Accessibility | `frontend/oms-ui/src/app/app.ts`, `app.html` | skip link, focus to page heading after navigation, `aria-live` regions |

### Demonstration-only vs production-grade

Production-grade practices visible in the code: standalone components only, lazy routes, signal inputs, typed forms, functional guards and interceptors, runtime configuration instead of build-time secrets, centralized error normalization so raw server text is never shown, strict compiler flags, tests for guards, interceptors and pure state functions, and accessibility work (skip link, focus management, status not by colour alone).

Honest limits: no `OnPush` and still zone.js (default change detection); no end-to-end proof in real browsers beyond headless Chrome via Karma and the Playwright suite in `tests/e2e` (which ships a mock backend, `tests/e2e/mock-backend/server.js`; this chapter does not claim it was run against the real services); no internationalisation; no service worker or offline support; no real-time updates (polling only); the token is stored in `sessionStorage` (XSS-readable, discussed in chapter 10); guards are cosmetic by design; a handful of non-null assertions and double casts remain; no `resource()` APIs (experimental in Angular 20) are used; the bundle-size budget exists but this chapter does not quote a measured bundle size because none is recorded in the docs.

## Common mistakes and gotchas

1. **Treating route guards as security.** They run in the user's browser. Enforce on the server and return 401/403/404 there.
2. **Forgetting `track` or using an unstable key in `@for`.** Using the array index as the key loses DOM identity on reorder; use an id.
3. **Calling `inject()` outside an injection context** (inside a `setTimeout`, a subscription callback, or a method called later). Capture the dependency in a field instead.
4. **Providing a service twice by accident.** `providedIn: 'root'` plus the same class in a component's `providers` creates a second instance; that is intentional for `OrderDetailStore` and a bug elsewhere.
5. **Mutating inputs or signal values in place.** `list.push(x)` on an array held by a signal does not notify; use `update(l => [...l, x])` as `CartStore` does.
6. **Unsubscribed observables.** Prefer `takeUntilDestroyed()` (used in the filter forms) or `toSignal`; the `async` pipe or signals avoid manual teardown.
7. **Subscribing inside a subscribe.** Use `switchMap`/`concatMap`; see chapter 9.
8. **Using `as` instead of checking.** `JSON.parse(x) as Session` silences the compiler without validating anything.
9. **Optional property access on index signatures.** With `noPropertyAccessFromIndexSignature`, write `route.data['roles']`, not `route.data.roles`.
10. **Retrying non-idempotent requests automatically.** A POST retry without an idempotency key can create duplicate orders; this repo retries only GET and HEAD.
11. **Putting secrets or environment switches in the bundle.** `environment.ts` and `config.json` are public. Only non-secret values belong there.
12. **Template method calls that do real work.** They run on every change detection pass. Use `computed`.
13. **Using `@Input()` aliases and `ngOnChanges` for everything.** Signal inputs plus `computed`/`effect` replace most of it, but remember `effect` is for side effects, not derived state.
14. **Assuming experimental APIs are stable.** `resource`, `rxResource` and `httpResource` are experimental in Angular 20; pin and isolate them.

## Quick-recall cheat sheet

* Standalone: `@Component({ imports: [...] })`, no NgModule, bootstrap with `bootstrapApplication(App, appConfig)`; `provideRouter`, `provideHttpClient(withInterceptors([...]))`, `provideAppInitializer`.
* Control flow: `@if / @else / @for (x of xs; track x.id) / @switch`; `track` required; no `*ngIf`.
* Inputs and outputs: `input()`, `input.required<T>()`, `output()`, `model()`; route params bind with `withComponentInputBinding()`.
* DI: `inject(Token)`; `providedIn: 'root'` singleton; component `providers` per instance; injection context rule.
* Routing: lazy `loadComponent: () => import(...)`; functional guards return `true | UrlTree`; `data` for static route metadata; `title` per route.
* Interceptors: `HttpInterceptorFn`, `req.clone()`, array order is request order, reverse order for responses; never attach tokens to foreign origins.
* Forms: typed reactive forms, `fb.nonNullable`, validators return `null | ValidationErrors`, map server `errors[]` onto controls.
* Change detection: this repo is zone-based, default strategy, signals everywhere; `OnPush` + signals is the cheap upgrade; zoneless is stable in 20.2; `resource`/`httpResource` experimental.
* Testing: `fakeAsync` + `tick`, `TestBed.runInInjectionContext` for guards, `HttpTestingController` + `verify()` for HTTP, pure functions without TestBed.
* TypeScript: unions and literal types for domain values; generics with constraints (`F extends object`); `unknown` + `instanceof`/type predicates; `Partial`, `Pick`, `Record`, `ReturnType`, `readonly`.
* Strict flags in this repo: `strict`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noImplicitReturns`, `noFallthroughCasesInSwitch`, `strictTemplates`.
* Say clearly: guards are UX; the backend enforces; the token in `sessionStorage` is an XSS trade-off; no OnPush here.

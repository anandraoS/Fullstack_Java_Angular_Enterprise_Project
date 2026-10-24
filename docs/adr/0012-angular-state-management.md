# ADR-0012: Angular state management with signals and small stores, not NgRx

* Status: Accepted
* Date: 2026-10
* Deciders: frontend lead

## Context

`frontend/oms-ui` (Angular 20, Angular Material) has about a dozen screens. Server data dominates; client-only state is small (auth, cart, list query/paging, polling status). The team has React/Redux background and wants a clear mapping. (Update 2026-10-19: a real React + Redux Toolkit UI now exists as a second front end, see ADR-0013; this ADR still governs the Angular app only.)

## Decision drivers

* Minimal boilerplate for the amount of shared state we have.
* Testability: state transitions as pure functions.
* Fine-grained change detection without a heavy runtime.
* Debuggability.

## Considered options

1. NgRx (store, effects, selectors, devtools).
2. NgRx SignalStore.
3. Plain Angular signals in injectable stores plus RxJS for IO (chosen).
4. Component-local state only.

## Decision outcome

Signals in `providedIn: 'root'` stores for cross-screen state (`AuthStore` in `core/auth/auth.store.ts`, `CartStore` in `core/state/cart.store.ts`) and per-screen stores for server-backed screens (`list-store.ts` for paginated lists, `order-detail.store.ts`). Reducer-style pure update functions (`listLoading`, `listLoaded`, `listFailed`, `withFilters`, `withPage`, `withSort`) are exported and unit-tested without TestBed. IO and polling use RxJS (`pollUntil` in `core/state/polling.ts`, backoff 1.5x capped by `pollMaxIntervalMs`). Derived values use `computed`. No global store: server state is refetched, not mirrored.

Revisit triggers: more than a few stores writing the same entity, need for time-travel/devtools, cross-tab undo, or an offline write queue. Then adopt NgRx SignalStore first (same signal model), full NgRx only for event-sourced client logic.

### Consequences

* Good: very little code, no action/reducer ceremony, signals integrate with templates and zoneless-ready change detection, tests are plain TypeScript.
* Bad: no built-in devtools or action log; conventions rather than enforced structure (discipline needed to keep stores small and updates pure); risk of ad hoc stores multiplying; no standard pattern for optimistic updates or caching of server state (hand-rolled).

## Alternatives rejected

* NgRx: excellent for large teams and complex client state, but here it would add actions, effects and selectors for data that is refetched anyway.
* Component-only state: breaks down for auth, cart and list state that survive navigation.

## React/Redux mapping (summary)

| Redux / React | This project |
|---|---|
| store slice | a store class with `signal()` fields |
| reducer | pure functions such as `listLoaded`, `withFilters` |
| selector / reselect | `computed()` |
| thunk / saga | RxJS pipelines, `pollUntil`, `switchMap` |
| `useSelector` | reading the signal in a template or `computed` |
| middleware | HTTP interceptors |

Full table: `docs/architecture/angular-architecture.md`.

## Links

* `frontend/oms-ui/src/app/core/state`, `frontend/oms-ui/src/app/core/auth`
* ADR-0010

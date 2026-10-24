# Angular standards (Angular 20, `frontend/oms-ui`)

Load when: touching `frontend/oms-ui`. Architecture and the React/Redux mapping: `docs/architecture/angular-architecture.md`;
decision: `docs/adr/0012`. The repository has a second, feature-equivalent UI in `frontend/oms-ui-react`: its rules are in
`03b-react-standards.md` (decision `docs/adr/0013`). A behaviour or contract change usually has to be made in both UIs.

* Standalone components only, `ChangeDetectionStrategy.OnPush`, new control flow (`@if`, `@for` with `track`).
* State: signals for local and store state (`signal`, `computed`); services (`*.store.ts`) own state and expose readonly
  signals. RxJS for streams (HTTP, polling); convert with `toSignal`. No NgRx unless an ADR supersedes 0012.
* HTTP: all calls through typed API classes (`core/api/apis.ts`); errors normalized by `core/http/api-error.ts`; an
  interceptor attaches the bearer token and correlation id, and maps 401/403/429. Components never build URLs.
* Subscriptions: prefer `async` pipe / `toSignal`; otherwise `takeUntilDestroyed()`. No `setInterval` + `subscribe`; poll
  with `pollUntil` (backoff, stop on terminal state or destroy). See flawed-ai-examples/11.
* Security: route guards are UX only (flawed-ai-examples/12); never put secrets in the bundle; no `innerHTML` /
  `bypassSecurityTrust*`; tokens kept in memory (check `auth.store.ts` for the current choice) and never logged.
* Runtime config from `config.json` (`apiBaseUrl`, `pollIntervalMs`, `pollMaxIntervalMs`), not build-time constants.
* Forms: typed reactive forms; client validation mirrors but never replaces server validation; show server `errors[]`
  per field; send an `Idempotency-Key` generated once per submit intent and reused on retry.
* Accessibility: labels for every input, `aria-live` for async status, keyboard operable dialogs, colour not the only
  status signal (status chips carry text).
* Tests: Jasmine/Karma specs next to the code (`*.spec.ts`); stores and pure functions tested without TestBed where
  possible; Playwright specs in `tests/e2e`.
* Lint/format: follow `eslint` and `prettier` config in the app; no `any` without a comment.

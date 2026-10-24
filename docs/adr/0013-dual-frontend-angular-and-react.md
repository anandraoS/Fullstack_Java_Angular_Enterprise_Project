# ADR-0013: Two front ends (Angular and React + Redux) on one backend contract

* Status: Accepted
* Date: 2026-10
* Deciders: frontend lead, platform lead

## Context

`frontend/oms-ui` (Angular 20, ADR-0012) was the only UI and the earlier rule was "Angular only". The target job description asks for React with Redux, and the same people maintain the platform. A second UI is cheap to justify only if it does not fork the backend, the contract, or the test suite. The gateway API, the problem+json error model, role rules, idempotency and the order state machine are already verified; they must not change for a UI.

## Decision drivers

* One API contract, no UI-specific endpoints, no backend changes.
* Evidence that both UIs behave the same: the SAME end-to-end specs must pass against both.
* Idiomatic code in each framework (not an Angular port written in JSX).
* Same security posture (CSP, same-origin `/api`, backend-authoritative authorization).
* Low cost to keep one UI and drop the other.

## Considered options

1. Replace Angular with React.
2. Keep Angular only and document React equivalents (the mapping table in the Angular README).
3. Add `frontend/oms-ui-react` as a second, feature-equivalent UI that shares nothing but the contract (chosen).
4. A shared UI core (TypeScript package) consumed by both.

## Decision outcome

Option 3. `frontend/oms-ui-react` is React 19 + TypeScript (strict) + Vite, Redux Toolkit, React Router, React Hook Form + Zod and MUI. It talks to the gateway through the same `/api/v1` paths, with the same behaviours: `Idempotency-Key` per submit attempt (reused only after an unknown outcome and an unchanged body), problem+json normalization, 401 logout, 429 toast with `Retry-After`, GET-only retry, role-aware navigation, order polling until a terminal status, and null-price tolerance for REJECTED orders (defect D-03).

State split: **RTK Query for server state** (cache, de-duplication, invalidation, polling), **slices for client state** (`auth`, `cart`, `ui`). No server data is copied into slices. See [react-architecture.md](../architecture/react-architecture.md) for the mapping to the Angular stores.

Parity is enforced by tests, not by prose: `tests/e2e` is parameterized with `E2E_UI=angular|react`, and the 18 lifecycle specs run unchanged against both, in mock-backend mode and against the live stack. The React markup was written to satisfy the existing role/label/text locators plus the few `data-testid`s Angular already exposes, so no adapter or forked spec was needed.

Deployment: separate image (`oms-ui-react`, same nginx-unprivileged contract), a compose profile `react` (host port 4201), an optional Kubernetes Deployment/Service that the Ingress does not route (the Angular UI owns `/`), and separate CI jobs. Removing the React UI means deleting one directory, one compose service, one kustomize resource and its CI jobs.

### Consequences

* Good: the contract is exercised by two independent clients, which finds contract ambiguity (for example the null prices of REJECTED orders). Interviews can compare the two stacks on identical behaviour.
* Good: no backend change; the React UI sits behind the same gateway, CORS-free (same origin through nginx or the Vite proxy).
* Bad: every UI feature is written twice and must be kept in step (mitigated by the shared e2e suite, not eliminated). Two dependency trees to patch (`npm audit` runs for both in CI).
* Bad: UI-level unit tests are not shared (Karma vs Vitest); only the e2e layer is.
* Bad: MUI's emotion runtime injects styles, so the CSP keeps `style-src 'unsafe-inline'` (as Angular Material already required); scripts stay `script-src 'self'`.

## Alternatives rejected

* Replacing Angular: loses a verified UI and the Angular-specific evidence the repository already has.
* Mapping table only: does not demonstrate working React/Redux code.
* Shared UI core package: the two frameworks share only types and a handful of pure functions (timeline, idempotency holder); the packaging overhead exceeds the reuse. The types are copied verbatim from the contract instead (`src/models.ts`).

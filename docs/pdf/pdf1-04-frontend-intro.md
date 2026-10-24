## Why there are two front ends

The original brief mandated Angular, so `frontend/oms-ui` (Angular 20, standalone components, signal stores, ADR-0012) was built first. The user later explicitly asked for a React frontend as well, so `frontend/oms-ui-react` (React 19, Redux Toolkit with RTK Query, React Router, MUI) was added as a second, feature-equivalent UI. The decision is ADR-0013. Three rules kept the second UI cheap and honest:

* **One contract, no backend change.** Both UIs call the same `/api/v1` gateway paths and follow the same problem+json errors, role rules, idempotency and order state machine. Nothing under `services/` or `gateway/` was changed for React, and nothing in the backend knows which UI is calling.
* **Parity is proven by tests, not prose.** The same 18 Playwright specs run unchanged against both UIs; `E2E_UI` selects which one.
* **Idiomatic code in each framework**, not an Angular port written in JSX: RTK Query and slices on one side, signal stores and RxJS on the other.

## Dual UI, one gateway

Both single-page apps are served same-origin with `/api` proxied to the gateway (the Vite or Angular dev server, `scripts/serve-ui.mjs`, or nginx in the container images), so no CORS is needed. The gateway is the only public entry point for either UI. In the local run the Angular UI is on port 4200 and the React UI on 4201 (`bash scripts/run-local.sh --ui both`); in Kubernetes the Angular UI owns the Ingress path `/` and the React UI is an optional Deployment that the Ingress does not route (reach it with `kubectl port-forward`). The system context diagram in section 2 shows both UIs in front of the same gateway.

| Aspect | Angular UI | React UI |
|---|---|---|
| Directory | `frontend/oms-ui` | `frontend/oms-ui-react` |
| Dev and local port | 4200 | 4201 |
| Language and build | TypeScript strict, Angular CLI | TypeScript strict, Vite |
| Unit tests | Karma and Jasmine, 149 of 149 | Vitest, Testing Library and MSW, 207 of 207 |
| End-to-end | 18 of 18 mock, 18 of 18 live | 18 of 18 mock, 18 of 18 live (`E2E_UI=react`) |
| Image and Kubernetes manifest | nginx-unprivileged image, base manifest | same pattern; never built or applied (no Docker, no cluster) |

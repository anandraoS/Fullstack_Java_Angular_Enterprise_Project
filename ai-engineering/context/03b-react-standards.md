# React standards (React 19 + Redux Toolkit, `frontend/oms-ui-react`)

Load when: touching `frontend/oms-ui-react`. Architecture: `docs/architecture/react-architecture.md`; decision:
`docs/adr/0013-dual-frontend-angular-and-react.md`; app README: `frontend/oms-ui-react/README.md`. Same gateway contract as the
Angular UI (`docs/architecture/00-shared-contract.md`); the Angular rules are in `03-angular-standards.md`. A behaviour change
in one UI normally needs the same change in the other, and the shared Playwright specs (`E2E_UI=angular|react`) must stay green
for both.

* TypeScript strict, function components and hooks only, no `any` without a comment. Dependencies stay pinned to exact versions.
* **Server state = RTK Query** (`src/store/api.ts`): endpoints with `providesTags` / `invalidatesTags`, polling through
  `pollingInterval`. Do not copy server data into slices and do not write fetch thunks by hand.
* **Client state = slices** (`authSlice`, `cartSlice`, `uiSlice`): small, serializable, pure helper functions plus
  `createSelector` selectors, tested without React. Screen-local state (filters, page, sort, dialog open) stays in the component
  (`useListQuery`, `useState`, React Hook Form) and is passed into RTK Query arguments.
* HTTP: everything through `omsBaseQuery` (`src/store/baseQuery.ts`): correlation id, bearer token (never on `/auth/token`),
  GET-only retry on network/502/503/504, problem+json normalized to a serializable `ApiError`, 401 logs out, 429 toasts.
  Components never build URLs and never show raw `detail`.
* Hooks: dependency arrays honest (`react-hooks` lint is on), no `setInterval` for polling (use `useOrderPolling`), derive state
  during render instead of `useEffect` where possible, clean up subscriptions and timers.
* Forms: React Hook Form + Zod; `POST /orders` carries an `Idempotency-Key` from `IdempotencyKeyHolder` (reuse only after an
  unknown outcome with an unchanged body; renew otherwise); submit disabled while in flight; map 400 field errors to controls.
* Routing: React Router layout routes with `React.lazy`; `RequireAuth` / `RequireRole` are UX only (flawed-ai-examples/12 applies);
  `returnUrl` only accepts same-app relative paths.
* Security: no `dangerouslySetInnerHTML`, no CDN assets, no secrets in the bundle, token never logged. The session lives in Redux
  memory plus `sessionStorage` (readable by script on the origin: say so, do not hide it). CSP keeps `style-src 'unsafe-inline'`
  only because MUI/Emotion injects styles; never widen `script-src`.
* Types: `src/models.ts` is a verbatim copy of the contract types (not generated). When the contract changes, update BOTH copies
  (Angular `models.ts` and this one) in the same change.
* Accessibility: labelled controls, `role="alert"` / `role="status"`, focus the page `h1` on mount, dialogs trap and restore focus,
  status is text plus glyph. State plainly that no axe/Lighthouse/screen-reader audit exists.
* Tests: Vitest + Testing Library + MSW (`src/test/`), render the app at a URL with a seeded store (`createAppStore()`); assert
  what the user sees, not implementation details; one failing test first for a bug. Run `npm test`, `npm run lint`,
  `npm run build` before claiming done. Report real numbers only (last run 2026-10-19: 207 tests, coverage 96.3 % statements).
* Do not report the Docker image, compose profile `react` or the Kubernetes manifest as verified: they were never built or applied.

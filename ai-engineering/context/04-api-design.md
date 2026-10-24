# API design rules

Load when: adding or changing an endpoint. Normative: contract section 5; compatibility: `docs/architecture/api-versioning.md`;
decision: `docs/adr/0007`.

* Base path `/api/v1`; nouns, plural; actions that are not CRUD are `POST /{resource}/{id}/{verb}` (e.g. `/orders/{id}/cancel`).
* Methods: GET safe+idempotent; PUT idempotent full update; DELETE deactivates (products); POST creates or acts.
* Status codes: 201 create (with `Location`), 202 accepted asynchronous work (`POST /orders`), 200 read/act, 204 none, 400
  validation, 401 unauthenticated, 403 forbidden (role), 404 not found or not yours, 409 state/optimistic conflict, 413,
  422 idempotency key reused, 429 with `Retry-After`, 503 upstream.
* Errors: RFC 7807 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, `code`,
  `correlationId`, `timestamp`, `errors[]` (always present). New failure mode = new `code`, additive only.
* Headers: `X-Correlation-Id` accepted/echoed; `Idempotency-Key` required on `POST /orders` (8-64 chars `[A-Za-z0-9_-]`).
* Pagination: `page` (0-based), `size` (default 20, max 100, out of range = 400), `sort=field,dir` whitelisted; envelope
  `{content, page, size, totalElements, totalPages}`.
* Compatibility inside v1: you may add optional request fields, response fields, endpoints, enum values (clients must
  tolerate unknown); you may not remove/rename fields, change types, tighten validation, or change status semantics.
  Breaking change = `/api/v2` with a deprecation window and `Deprecation`/`Sunset` headers.
* DTOs are separate from entities. Money = number with scale 2 plus `currency`. Dates ISO-8601 UTC.
* Authorization is declared per endpoint (roles) AND enforced per object (ownership). Document both in OpenAPI.
* OpenAPI: springdoc annotations on every controller; committed export under `specs/openapi/<service>.json`; a PR that
  changes the API must update it. `node scripts/export-openapi.mjs --check` detects drift against a running stack (verified 2026-10-18); it is not wired into `ci.yml` yet.

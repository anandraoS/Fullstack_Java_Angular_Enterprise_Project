# ADR-0007: URL-path API versioning with additive-only evolution

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

External clients (the Angular UI, scripts, possibly partners) depend on the REST surface behind the gateway. We need a visible version scheme and a cheap, enforceable compatibility discipline.

## Decision drivers

* Visibility in logs, routes, gateway rules and curl examples.
* Ability to run two major versions side by side behind the gateway.
* Most changes should not require a new major version.

## Considered options

1. URL path (`/api/v1/...`).
2. Header or media-type versioning (`Accept: application/vnd.oms.v1+json`).
3. Query parameter (`?version=1`).
4. No versioning, rely on discipline.

## Decision outcome

URL-path major version, base path `/api/v1`. Within v1 only additive, backward-compatible changes are allowed (new optional request fields, new response fields, new endpoints, new enum values documented as extensible only where clients are tolerant). Removing or renaming fields, changing types or semantics, or tightening validation requires `/api/v2`. Deprecation uses `Deprecation` and `Sunset` response headers plus a minimum notice period. Enforcement: committed OpenAPI exports in `specs/openapi/<service>.json`, an OpenAPI diff step in CI that fails on breaking changes, and consumer-driven contract tests. The gateway routes by path prefix, so v2 can be added as a new route to a new controller set without touching v1. Full rules and tables are in `docs/architecture/api-versioning.md`.

### Consequences

* Good: obvious to humans and tooling, trivial gateway routing and rate-limit rules, cacheable, easy to test per version.
* Bad: URL is not "pure REST" (resource identity includes version); two live versions double the test and support surface; clients must tolerate unknown fields (the UI models are written that way); enum growth is a breaking change for strict clients, so enums are documented as open.

## Alternatives rejected

* Media-type versioning: elegant but hard to see in logs, awkward in browsers and Postman, and gateway routing by header is more fragile.
* Query parameter: easy to omit, pollutes caching keys, no clear default.
* No versioning: no escape hatch when a breaking change becomes unavoidable.

## Links

* Contract section 5; `docs/architecture/api-versioning.md`; `specs/openapi` (exports are generated from each service's `/v3/api-docs`)
* ADR-0002 (events use topic and `schemaVersion` versioning instead)

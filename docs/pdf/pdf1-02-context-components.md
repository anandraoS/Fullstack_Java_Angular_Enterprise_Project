## System context

The platform is used by three human roles through either of two browser applications (Angular and React, same contract), and by machines through the same REST API. Everything outside the dashed boundary in the diagram is either infrastructure (Kafka, PostgreSQL, Redis, the observability stack) or an external party that is simulated: the identity provider (a dev-only token endpoint stands in for it) and the email or SMS provider (a simulated sender that never leaves the process).

Reading the diagram:

* The browser (either UI) talks only to the gateway. The gateway is the single public entry point and the only component that mints demo tokens.
* Services never call each other through the gateway. The one synchronous service-to-service call (order to inventory validation) goes directly over the cluster network with a short-lived SERVICE token.
* Kafka carries commands and events between services. Each service reads and writes only its own database.

## Context diagram

{{diagram:01-system-context|System context: actors, platform boundary, infrastructure and simulated externals}}

## Component and service map

The second diagram zooms into the four services. Inside each service the layering is the same: REST controllers, application services with transaction boundaries, domain classes without framework dependencies (`OrderStateMachine`, `FulfillmentStateMachine`), repositories, messaging listeners, scheduled jobs, and the outbox relay from `platform-common`.

{{diagram:02-component-service-map|Component and service map with data ownership}}

## Where the code lives

| Area | Path |
|---|---|
| Reactor build | `services/pom.xml` (gateway module in `gateway/`) |
| Shared library | `services/platform-common/` |
| Test helpers | `services/platform-test-support/` |
| Local no-Docker stack (embedded Kafka) | `services/local-stack/` |
| Order, inventory, fulfillment, notification | `services/<name>-service/` |
| Edge | `gateway/` |
| Angular UI | `frontend/oms-ui/` |
| React UI (second front end, same contract) | `frontend/oms-ui-react/` |
| Container, Compose, Kubernetes, AWS | `infrastructure/` |
| Dashboards, alerts, collectors | `observability/` |
| Load tests | `performance/` |
| AI workspace | `ai-engineering/` |
| Specs and OpenAPI exports | `specs/` |

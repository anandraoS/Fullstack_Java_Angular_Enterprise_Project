## Purpose of this document

This document explains the architecture of an Order Management and Fulfillment platform (OMS) that was built as a runnable, interview-preparation reference for a Senior Full Stack Java and Angular engineer role. The original brief mandated Angular; the user later explicitly asked for a React frontend as well, so the platform has two feature-equivalent browser UIs (Angular 20 and React 19 with Redux Toolkit) on one unchanged gateway contract (ADR-0013). It is written so that a reader can explain every design choice, point at the code that implements it, and state honestly which parts were executed and which are documented only. All data in the system is synthetic. There are no real customers, no real payment or email providers and no real credentials.

> Reading guide: each section ends with the evidence you would show in an interview. Evidence labels used everywhere are **Executed** (run in this environment, output recorded), **Documented / not executed** (files exist, were statically checked at most) and **Conceptual** (discussed, not built).

## The business problem

A mid-sized B2B supplier sells physical goods to business customers. The operations team has five recurring problems:

| Problem in the business | Consequence | What the platform does about it |
|---|---|---|
| Two buyers order the last units at the same moment | Overselling, cancelled orders, angry customers | Stock is reserved with one atomic conditional database update, so the database decides who wins |
| A customer double-clicks "Place order" or the network retries | Duplicate orders | `Idempotency-Key` header, fingerprint check, stored replay response |
| A step in the order flow fails halfway (stock reserved, shipping fails) | Stock locked forever, money or goods in limbo | Orchestrated saga with compensation, recovery job and reconciliation |
| Staff cannot see why an order is stuck | Long investigations, guesswork | Status history, saga step log, audit events, correlation ids, metrics, runbooks |
| Notifications go out twice, or never | Customer confusion | Idempotent consumers, unique (event, template) notification key, retry with backoff |

## Actors

* **Customer** (role `CUSTOMER`): browses the catalog, places and cancels their own orders, reads their own notifications.
* **Operator** (role `OPERATOR`): sees every order, adjusts stock, retries failed orders, fulfillments and notifications, reads audit and messaging health.
* **Administrator** (role `ADMIN`): everything an operator can do plus product and customer management and reconciliation.
* **Service** (role `SERVICE`): machine identity used for service-to-service REST calls on `/api/v1/internal/**`.

## Functional requirements

1. Authenticate with a JWT; authorise by role and by resource ownership (a customer only sees their own orders).
2. Browse products and availability; staff manage products and adjust stock with a movement ledger.
3. Place an order with several lines. The API answers `202 Accepted` immediately and processing continues asynchronously.
4. Orders move through a strict lifecycle: `CREATED`, `VALIDATED`, `INVENTORY_RESERVED`, `CONFIRMED`, `FULFILLED`, or the terminal outcomes `REJECTED`, `CANCELLED`, `FAILED`, with `COMPENSATION_PENDING` as the transitional compensation state.
5. Reserve stock without ever overselling; reservations expire after a TTL if the order is not confirmed.
6. Simulated fulfillment (picking, packing, shipping, completion) with deterministic failure injection (`FAILTOWN`).
7. Simulated email notifications with retry, backoff and duplicate protection.
8. Cancel an order from any non-terminal pre-fulfillment state and release stock through compensation.
9. Operational tooling: failed-order list, saga inspection, retry, reconciliation run, messaging and audit endpoints, aggregate health.
10. A browser UI for customers and operators, delivered twice on the same contract: Angular 20 (`frontend/oms-ui`) and React 19 with Redux Toolkit (`frontend/oms-ui-react`).

## Non-functional requirements

| Quality | Target in this design | How it is addressed |
|---|---|---|
| Correctness under concurrency | No negative or oversold stock, ever | Atomic conditional UPDATE, database CHECK constraints, tests under concurrency |
| Reliability | No lost events, no duplicated effects | Transactional outbox, idempotent consumers, DLT, recovery job |
| Availability | One service failing must not take down the others | Database per service, async messaging, circuit breaker at the edge and on REST calls |
| Security | OWASP API Top 10 aware | JWT at edge and service, ownership checks, rate limits, body limits, masked logs, no secrets in code |
| Observability | Any order traceable end to end | Correlation id, W3C traceparent, structured logs, Prometheus metrics, Grafana dashboards, runbooks |
| Evolvability | Add fields without breaking consumers | URL versioning, additive-only changes, tolerant readers, schemaVersion in the envelope |
| Operability | Repeatable build and deploy | Maven reactor, Docker images, Kustomize overlays, GitHub Actions, rollback procedure |
| Testability | Fast feedback on domain rules | Pure `OrderStateMachine`, H2 in PostgreSQL mode, embedded Kafka, BDD scenarios |

## Explicit scope boundaries and assumptions

* Payment, tax, pricing rules, real shipping carriers and real email or SMS delivery are out of scope; they are simulated or absent.
* The identity provider is simulated by a dev-only token endpoint in the gateway. The resource-server configuration is OIDC compatible, so a real issuer can be plugged in by configuration.
* Capacity targets are illustrative design targets, not measured results. Load scripts exist (`performance/`) but a production-scale run was not performed.
* The reference platform runs locally without Docker (embedded Kafka and H2). Docker, Kubernetes and AWS artefacts exist and are validated statically; whether they were run live is stated in the evidence section.

## Technology choices at a glance

| Layer | Choice | Version | Why |
|---|---|---|---|
| Language and runtime | Java | 17 | LTS, records and sealed types, widely available in enterprises |
| Framework | Spring Boot | 3.5.16 | Mature ecosystem, Actuator, auto-configuration |
| Edge | Spring Cloud Gateway (reactive) with Resilience4j | Spring Cloud 2025.0.0, Resilience4j 2.2.0 | Routing, filters, circuit breaker, rate limiting |
| Messaging | Apache Kafka | 3.9 (KRaft) in Compose | Durable log, per-key ordering, consumer groups |
| Database | PostgreSQL | 16 (H2 in PostgreSQL mode for local and tests) | ACID, row locks, rich indexing |
| Migrations | Flyway | via Spring Boot | Same SQL for H2 and PostgreSQL |
| Cache and limiter store | Redis | optional | Only for rate limiting and product cache; degrades to in-memory |
| Frontend | Angular | 20 | Standalone components, signals, functional guards and interceptors |
| Frontend (second UI) | React, Redux Toolkit (RTK Query), React Router, MUI, Vite | React 19, RTK 2, Router 7 | Slices for client state, RTK Query for server state, same gateway contract and same 18 end-to-end specs |
| Packaging | Docker, Kustomize, Terraform (AWS reference) | n/a | Immutable images, environment overlays |
| CI | GitHub Actions | n/a | Build, test, scan, image, validate |
| Observability | Micrometer, OpenTelemetry, Prometheus, Grafana, Loki, Tempo | n/a | Metrics, logs, traces |

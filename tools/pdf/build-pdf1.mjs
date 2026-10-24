// Builds PDF 1: Fullstack_Java_Angular_Platform_Architecture.pdf (21 sections + traceability appendix).
// Rebuild any time (`node build-pdf1.mjs` in tools/pdf): test statistics and docs/validation/validation-report.md
// are read from disk at build time, so a rebuild after more runs reports the latest facts.
import fs from 'node:fs';
import path from 'node:path';
import { PdfBuilder } from './lib/pdf-engine.mjs';
import { DOCS, FOOTER_LINES, read, exists, shiftHeadings, dropTitle, stripLocalLinks, join, sub } from './lib/common.mjs';
import { expandIncludes } from './lib/expand.mjs';
import { matrixCards } from './lib/matrix.mjs';
import { testStatsMarkdown, validationMarkdown, defectsMarkdown } from './lib/evidence.mjs';

const OUT = path.join(DOCS, 'pdf', 'Fullstack_Java_Angular_Platform_Architecture.pdf');
const doc = (rel) => stripLocalLinks(read(rel));
const body = (rel) => dropTitle(doc(rel));
const own = (rel) => read(rel);
/** Extracts a "## N." section of a markdown file (heading line through the line before the next "## "). */
function slice(rel, headingRe) {
  const lines = read(rel).split('\n');
  const start = lines.findIndex((l) => /^## /.test(l) && headingRe.test(l));
  if (start < 0) throw new Error(`section ${headingRe} not found in ${rel}`);
  let end = lines.findIndex((l, i) => i > start && /^## /.test(l));
  if (end < 0) end = lines.length;
  return stripLocalLinks(shiftHeadings(lines.slice(start, end).join('\n'), 0));
}
const adrs = fs.readdirSync(path.join(DOCS, 'adr')).filter((n) => /^00(0[1-9]|1\d)-/.test(n)).sort();
const runbooks = fs.readdirSync(path.join(DOCS, 'runbooks')).filter((n) => /^0\d-/.test(n)).sort();

// docs/api/examples.md is maintained by the integration run; include it only when it uses the current ids.
let apiExamples = '';
if (exists('docs/api/examples.md')) {
  const t = read('docs/api/examples.md');
  apiExamples = /p0000000-/.test(t)
    ? '> The captured API examples file contains outdated product ids (`p0000...`) and is intentionally left out of this build. Rebuild after it has been refreshed.'
    : '## Captured API examples\n\n' + shiftHeadings(stripLocalLinks(dropTitle(t)), 1);
}

const erIntro = `## One database per service

Each service owns an isolated schema (\`order_db\`, \`inventory_db\`, \`fulfillment_db\`, \`notification_db\` on one PostgreSQL instance in Compose; separate in-memory H2 per service locally). The diagrams below are derived from the Flyway migrations, not from memory: shared tables from \`services/platform-common/src/main/resources/db/common/V1__outbox_processed_audit.sql\` appear in every service database, service tables come from each service's \`V10\` migration (order-service also \`V11\` for saga and idempotency tables and \`V12\` for indexes).

| Service database | Migrations | Tables |
|---|---|---|
| order_db | V1, V10, V11, V12 (+ V1000 dev seed) | customers, orders, order_items, order_status_history, saga_instances, saga_step_log, idempotency_keys, outbox_events, processed_events, audit_events |
| inventory_db | V1, V10 (+ V1000 dev seed) | products, stock, inventory_reservations, inventory_reservation_items, stock_movements, outbox_events, processed_events, audit_events |
| fulfillment_db | V1, V10 | fulfillments, fulfillment_events, outbox_events, processed_events, audit_events |
| notification_db | V1, V10 | order_contacts, notifications, notification_attempts, processed_events, audit_events (outbox is present from V1 but unused: the service only consumes) |

### Design notes

* Foreign keys exist only inside a service. Across services there are only identifiers (\`orders.customer_id\` is local; \`order_items.product_id\` is a plain UUID owned by inventory, so it carries no foreign key).
* Optimistic locking uses a \`version\` column on orders, saga instances, products, stock, reservations and fulfillments.
* Uniqueness encodes business invariants: one saga per order, one reservation per order, one fulfillment per order, one notification per (source event, template), one idempotency record per (scope, key), one processed-event row per (consumer, event id).
* CHECK constraints on \`stock\` (non-negative, reserved at most on_hand) are the last line of defence for the oversell invariant.
* Money is \`DECIMAL(19,2)\` or \`NUMERIC(12,2)\`; timestamps are \`TIMESTAMP WITH TIME ZONE\`; the SQL stays portable so the same Flyway files run on H2 (PostgreSQL mode) and PostgreSQL 16.

{{diagram:04a-er-order-service|ER diagram: order_db}}

{{diagram:04b-er-inventory-service|ER diagram: inventory_db}}

{{diagram:04c-er-fulfillment-notification|ER diagrams: fulfillment_db and notification_db}}

### Indexing highlights

Order list queries are served by composite indexes (\`ix_orders_customer_status_created\`, \`ix_orders_status_created\`), the saga recovery scan by \`ix_saga_state_updated\`, reservation expiry by \`ix_reservations_status_expiry\` (the EXPLAIN ANALYZE study in \`docs/database/inventory-explain-analyze.md\` reports the effect), the outbox relay by \`ix_outbox_pending\`, and the notification delivery job by \`ix_notifications_due\`.`;

const stateIntro = `## Rules

The lifecycle is a pure, table-driven function \`(status, trigger) -> decision\` in \`OrderStateMachine\`: no Spring, no I/O, no clock. A decision is one of TRANSITION (valid), NO_OP (the trigger's effect is already in place: a duplicate or replayed event) or INVALID (a user command becomes 409 \`INVALID_STATE_TRANSITION\`; an asynchronous event is ignored and audited). Every transition writes \`order_status_history\`, \`saga_step_log\`, \`audit_events\` and, when relevant, an outbox event in ONE database transaction.

{{diagram:05-order-state-machine|Order lifecycle state machine}}

Terminal states are FULFILLED, REJECTED, CANCELLED and FAILED. COMPENSATION_PENDING is transitional: it means a \`ReleaseInventory\` command is outstanding.

{{code:services/order-service/src/main/java/com/enterprise/oms/order/domain/OrderStateMachine.java|static \\{|26|java}}

### Transition table (shared contract, section 6)`;

const kafkaIntro = `## Topology

Five topics with three partitions each (replication 1 locally, 3 in the production design), keyed by \`orderId\` so events for one order are ordered within a partition. Each failing consumer dead-letters to \`<topic>.DLT\`. Consumer group id equals the service name.

{{diagram:06-kafka-topic-event-flow|Kafka topics, producers, consumers and dead-letter flow}}

{{code:services/platform-common/src/main/java/com/enterprise/oms/common/events/Topics.java|public|18|java}}`;

const cicdText = `## Pipeline

The workflow in \`.github/workflows/ci.yml\` runs build and test, frontend build, four security scanners, infrastructure validation, image build with an image scan, a local smoke test on the embedded-Kafka stack, and a test-environment deploy that is a documented placeholder. Honest status: **the workflow has not run on a GitHub runner**; its YAML and the validation scripts it calls were checked locally where possible.

{{diagram:11-cicd-pipeline|CI/CD pipeline}}

### Stage purposes

| Stage | Purpose | Failure policy |
|---|---|---|
| java-build-test | Compile with Java 17, run unit and slice tests, package jars once | Fails the pipeline |
| java-integration | Failsafe tests, Testcontainers where Docker exists | Fails the pipeline |
| frontend | Install, lint, unit test, production build of the Angular app | Fails the pipeline |
| codeql, semgrep | Static analysis for code vulnerabilities | Report and gate per configuration |
| dependency-scan | Trivy on Maven and npm lockfiles and IaC, \`npm audit\` | HIGH or CRITICAL fixable findings fail |
| secret-scan | gitleaks | Any finding fails |
| infra-validate | kustomize build, kubeconform, terraform validate, compose config, observability checks | Fails the pipeline |
| docker, docker-ui | Build images from the already-built jars, scan them, push to GHCR on main and tags only | Image scan fails on fixable HIGH or CRITICAL |
| smoke-local | Start the embedded-Kafka stack and run \`scripts/smoke-test.mjs\` | Fails the pipeline |
| deploy-test | Placeholder apply to a non-production cluster plus smoke test | Needs a kubeconfig secret that is not provided |

### Release and rollback

Environments are Kustomize overlays (dev, test, prod). Production promotes an image by digest, applies after a server-side dry run, and watches the rollout; the rollback decision tree is in section 12. Database migrations follow expand and contract so that rolling back the application never needs a database rollback. Blue-green and canary are described conceptually in the interview guide; the repository implements rolling updates only.

{{code:.github/workflows/ci.yml|^  java-build-test:|22|yaml}}`;

const obsIntro = `## Three signals, one correlation id

Metrics (Micrometer to Prometheus), logs (Logstash JSON encoder to Promtail to Loki) and traces (OpenTelemetry bridge to the collector to Tempo) share one correlation id and the W3C trace context. Grafana provides the single pane: five provisioned dashboards (API RED, JVM, Hikari pool, messaging, saga funnel). Alert rules live in \`observability/prometheus/rules/oms-alerts.yml\` and each alert maps to a runbook.

{{diagram:12-observability-architecture|Observability architecture: logs, metrics, traces}}

### What to say about maturity

* Implemented and unit tested: correlation id filter, log masking, Micrometer metrics for events, outbox, saga, inventory reservations, operational endpoints.
* Verified against the live stack: all 59 dashboard and alert expressions resolve to metric names present in a real scrape of the five services (\`scripts/check-metric-names.mjs\`; the first run found 5 missing names, fixed as D-05 to D-07), plus a correlation trace across five services (\`docs/validation/correlation-trace-sample.md\`).
* Statically checked: dashboards and compose wiring (\`scripts/check-observability.py\`).
* Not executed: the Docker observability stack (no Prometheus, Grafana, Tempo or Loki ran, so dashboards were not rendered and alert rules were not evaluated). The \`traceparent\` is not continuous across Kafka hops; the correlation id is the join key.

{{code:observability/prometheus/rules/oms-alerts.yml|alert:|30|yaml}}`;

const mindText = `One page to recall the whole platform. Use it as the opening sketch in an interview, then drill into whichever branch the interviewer picks.

{{diagram:16-architecture-mind-map|Architecture mind map}}`;

const aiText = '';

const reactTesting = `## Front-end testing: React numbers and the dual-UI end-to-end matrix

The React UI was verified on 2026-10-19: **207 of 207 Vitest tests pass** (9 files: slices and selectors, error normalization, the RTK Query layer against a Mock Service Worker, guards, forms, idempotency, polling, every screen), coverage **96.3 % statements, 93.5 % branches, 94.5 % functions, 96.7 % lines**, ESLint 0 errors, production build OK and \`npm audit\` 0 vulnerabilities. The Angular UI has 149 of 149 Karma tests.

| Playwright, same 18 specs | Mock backend | Live stack |
|---|---|---|
| Angular (\`E2E_UI=angular\`) | 18 / 18 | 18 / 18 |
| React (\`E2E_UI=react\`) | 18 / 18 | 18 / 18 |

The live stack used the gateway on port 18085 because a foreign process held 18080. Not executed for the React UI: the Docker image, the compose profile, the Kubernetes manifest, CI runs, accessibility audits and a narrow-screen visual check. Unit tests are per framework; only the end-to-end layer is shared, which is what proves behavioural parity.`;

const feSecurity = `## Front-end security: token storage and Content Security Policy (Angular and React)

Both UIs treat the browser as untrusted: route guards are UX only and the backend authorizes every call. They share the same token trade-off and the same CSP caveat.

| Topic | Angular UI | React UI |
|---|---|---|
| Token storage | signal store in memory, mirrored to \`sessionStorage\` | Redux \`auth\` slice in memory, mirrored to \`sessionStorage\` by middleware |
| Exposure | readable by any script on the origin (XSS); per tab, cleared on close; never \`localStorage\`, never in URLs or logs | same |
| Production recommendation | authorization code with PKCE and a backend-for-frontend using HttpOnly, Secure, SameSite cookies | same |
| Where the token is attached | auth interceptor, API calls only, never \`/auth/token\` | \`omsBaseQuery\` \`prepareHeaders\`, API calls only, never \`/auth/token\` |
| Logout | clears the session | clears the session, resets the RTK Query cache and the cart |
| Redirect safety | n/a | \`returnUrl\` only accepts same-app relative paths |
| XSS surface | Angular escapes interpolation; no \`innerHTML\` | React escapes text nodes; no \`dangerouslySetInnerHTML\` |
| CSP (nginx) | \`script-src 'self'\`, \`style-src 'self' 'unsafe-inline'\` | same header; \`'unsafe-inline'\` for styles is needed because MUI/Emotion injects styles at runtime |
| Open item | CSP read from the file, image never built | nonce-based Emotion cache would remove the style exception; not done; image never built |

The React nginx policy as shipped in \`frontend/oms-ui-react/nginx.conf\`:

{{code:frontend/oms-ui-react/nginx.conf|add_header Content-Security-Policy|1|nginx}}`;

const sections = [
  { title: '1. Business problem and requirements', md: own('docs/pdf/pdf1-01-business-requirements.md') },
  { title: '2. System context and component diagrams', md: own('docs/pdf/pdf1-02-context-components.md') },
  { title: '3. Service boundaries', md: own('docs/pdf/pdf1-03-service-boundaries.md') },
  { title: '4. Frontend architecture: Angular and React', md: join(
      own('docs/pdf/pdf1-04-frontend-intro.md'),
      '## Angular architecture',
      shiftHeadings(body('docs/architecture/angular-architecture.md'), 1),
      '### Architecture diagram',
      '{{diagram:03-angular-architecture|Angular architecture: routes, guards, stores and HTTP pipeline}}',
      '{{code:frontend/oms-ui/src/app/core/http/interceptors.ts|export const retryInterceptor|12|typescript}}',
      '## React architecture',
      shiftHeadings(body('docs/architecture/react-architecture.md'), 1),
      '### Architecture diagram',
      '{{diagram:react-architecture|React architecture: router, Redux store with RTK Query, hooks, MUI and test layers}}',
      '{{code:frontend/oms-ui-react/src/routes.tsx|<Route element=.<RequireAuth />|15|tsx}}',
      own('docs/pdf/pdf1-04-frontend-react.md')) },
  { title: '5. REST API design', md: join(own('docs/pdf/pdf1-05-rest-api-design.md'), '{{diagram:17-rest-order-creation-flow|REST order creation: authorization, idempotency and async acceptance}}', apiExamples) },
  { title: '6. Database ER diagram', md: erIntro },
  { title: '7. Order lifecycle and state machine', md: join(stateIntro, slice('docs/architecture/00-shared-contract.md', /^## 6\./).replace(/^## .*\n/, '')) },
  { title: '8. Kafka event flows', md: join(kafkaIntro, slice('docs/architecture/00-shared-contract.md', /^## 7\./).replace(/^## .*\n/, '## Contract: topics, envelope, payloads and delivery semantics\n'), '## Delivery semantics in depth', body('docs/architecture/delivery-semantics.md')) },
  { title: '9. Saga orchestration and compensation', md: join(body('docs/architecture/saga-and-consistency.md'), '## Sequence diagrams', '{{diagram:07a-saga-happy-path|Saga happy path}}', '{{diagram:07b-saga-compensation|Saga compensation: fulfillment failure, cancellation and stuck release}}', '{{diagram:07c-saga-rejection-paths|Saga rejection paths: validation, insufficient stock, expiry}}', slice('docs/architecture/00-shared-contract.md', /^## 8\./).replace(/^## .*\n/, '## Contract: saga rules\n')) },
  { title: '10. Inventory concurrency handling', md: join(own('docs/pdf/pdf1-10-inventory-concurrency.md'), '## Sequence: check-then-act versus atomic update', '{{diagram:08-inventory-concurrency|Inventory concurrency: lost update versus atomic conditional update}}') },
  { title: '11. Security architecture', md: join('## Authentication and authorization flow', '{{diagram:09-security-auth-flow|Security architecture and authentication flow}}', '{{code:gateway/src/main/java/com/enterprise/oms/gateway/security/EdgeSecurityConfig.java|^    @Bean|28|java}}', feSecurity, body('docs/security/threat-model.md').replace(/```mermaid[\s\S]*?```/, '{{diagram:18-threat-model-dfd|Threat model data flow with trust boundaries}}')) },
  { title: '12. Docker and Kubernetes deployment', md: join(own('docs/pdf/pdf1-12-docker-kubernetes.md'), '## Deployment diagram', '{{diagram:10-deployment-docker-k8s-aws|Deployment: local, Docker Compose, Kubernetes and AWS reference}}', '## Deployment and rollback procedure', sub('docs/governance/deployment-and-rollback.md', 0).replace(/^# .*\n/, '')) },
  { title: '13. CI/CD pipeline', md: cicdText },
  { title: '14. Observability architecture', md: obsIntro },
  { title: '15. Failure and recovery flows', md: join(own('docs/pdf/pdf1-15-failure-recovery.md'), '{{diagram:13-failure-recovery-flows|Failure and recovery flows}}') },
  { title: '16. Testing strategy', md: join(own('docs/pdf/pdf1-16-testing-intro.md'), reactTesting, testStatsMarkdown(), validationMarkdown()) },
  { title: '17. AI-assisted development workflow', md: join(own('docs/pdf/pdf1-17-ai-workflow.md'), '{{diagram:15-ai-assisted-workflow|AI-assisted development workflow with human gates}}', aiText) },
  { title: '18. Architecture Decision Records', md: join(body('docs/adr/README.md'), ...adrs.map((f) => shiftHeadings(doc(`docs/adr/${f}`), 1))) },
  { title: '19. Architecture mind map', md: mindText },
  { title: '20. Troubleshooting scenarios', md: join(own('docs/pdf/pdf1-20-troubleshooting-intro.md'), '## Runbook index', body('docs/runbooks/README.md'), ...runbooks.map((f) => shiftHeadings(doc(`docs/runbooks/${f}`), 1))) },
  { title: '21. Limitations and future enhancements', md: own('docs/pdf/pdf1-21-limitations.md') },
  { title: 'Appendix A. Job-description traceability matrix', md: matrixCards() },
  { title: 'Appendix B. Integration defects found and fixed', md: defectsMarkdown() },
].map((s) => ({ ...s, md: expandIncludes(s.md, s.title) }));

const b = new PdfBuilder({
  outFile: OUT,
  title: 'Fullstack Java and Angular Enterprise Platform: Architecture',
  subtitle: 'Order Management and Fulfillment platform. Design, trade-offs, failure handling and honest verification status.',
  coverLines: [
    'Document: PDF 1 of 2, Architecture (21 sections, a traceability appendix and an integration-defects appendix)',
    'Stack: Java 17, Spring Boot 3.5.16, Spring Cloud Gateway, Resilience4j, Kafka, PostgreSQL 16, Flyway, Redis (optional), Angular 20, React 19 with Redux Toolkit, Docker, Kubernetes, Terraform (AWS reference), GitHub Actions, OpenTelemetry, Prometheus, Grafana',
    'Companion: Fullstack_Java_Angular_Deep_Dive_Interview_Guide.pdf',
    `Built: ${new Date().toISOString().slice(0, 10)}. Test statistics and the validation report are read from disk at build time; rebuild after the integration run.`,
  ],
  footerLines: FOOTER_LINES,
  tocLevels: 2,
});
b.build(sections.map((s) => ({ ...s, baseDir: DOCS })));
const pages = b.doc.bufferedPageRange().count;
await b.save();
console.log(JSON.stringify({ out: OUT, pages, stats: b.stats }, null, 2));

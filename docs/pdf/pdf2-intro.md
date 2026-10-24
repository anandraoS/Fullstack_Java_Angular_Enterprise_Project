## What this guide is

A deep reference and interview workbook for a Senior Full Stack Java and Angular role, built around a real, runnable codebase: an Order Management and Fulfillment platform (Java 17, Spring Boot 3.5, Spring Cloud Gateway, Kafka, PostgreSQL, Angular 20 and a React 19 + Redux Toolkit UI, Docker, Kubernetes). Every chapter explains a topic with a kid-friendly analogy, then three difficulty tiers (beginner, intermediate, advanced) with at least two concrete examples each, taken from this repository. The second half is a question bank with detailed model answers.

Companion document: *Fullstack_Java_Angular_Platform_Architecture.pdf* (the architecture, diagrams and evidence).

## How to use it

1. Read the chapter for a topic. Start with the analogy, then work through the tiers in order; stop at the tier that matches the role you are interviewing for.
2. Open the code paths cited in the chapter and read them. Citing a real class is more convincing than reciting a definition.
3. Answer each question aloud before reading the model answer. Compare, then note what you missed.
4. Practise the scenario questions with a whiteboard or paper: draw the flow, name the failure modes, state the trade-off.

## Evidence honesty

Answers separate three things and so should you in an interview:

* **Executed**: run in this environment with a recorded result (for example the concurrency test across three reservation strategies, the 207-case state machine matrix, the gateway security suite).
* **Documented / not executed**: files and designs exist but were not run, mainly Docker images, Kubernetes manifests, Terraform, k6 load tests, the GitHub Actions pipeline and PostgreSQL integration tests (Docker was not available on the development machine).
* **Conceptual**: discussed to show design thinking (OIDC with RS256, service mesh mTLS, blue-green, canary), not built.

Demonstration-only choices are named where they appear: HS256 shared-secret JWT and a dev token endpoint, embedded single-broker Kafka, H2 in PostgreSQL mode, static route URIs instead of Eureka and Config Server. The original brief mandated Angular; the user later explicitly asked for a React frontend as well, so the repository has two feature-equivalent UIs on one gateway contract (`frontend/oms-ui` and `frontend/oms-ui-react`, ADR-0013). Chapter 12b is implementation-backed: every React snippet is a real file, verified at build time, and the React UI passed 207 unit tests and the same 18 end-to-end specs as the Angular UI (2026-10-19). Not verified for React: the container image, the Kubernetes manifest, CI runs and accessibility audits.

## The twelve must-practise scenarios

| # | Scenario | Question number |
|---|---|---|
| 1 | Design a high-traffic order platform | Q25 |
| 2 | Prevent overselling inventory | Q42 |
| 3 | Design a saga across order and inventory services | Q41 |
| 4 | Handle duplicate Kafka events | Q34 |
| 5 | Recover a failed Kafka consumer | Q35 |
| 6 | Troubleshoot slow REST APIs | Q20 |
| 7 | Optimize a slow PostgreSQL query | Q49 |
| 8 | Secure service-to-service communication | Q78 |
| 9 | Deploy Spring Boot microservices to Kubernetes | Q87 |
| 10 | Test concurrent reservations | Q104 |
| 11 | Troubleshoot Kafka consumer lag | Q115 |
| 12 | Review AI-generated code | Q124 |

## Contents

Chapters 01 to 17 cover: Java 17 and JVM internals; Spring Boot and Spring Cloud; REST design; microservices and distributed systems; Kafka and event-driven design; saga and eventual consistency; PostgreSQL and query optimisation; Angular and TypeScript; RxJS and Signals; Spring Security and OAuth; Docker and Kubernetes; cloud deployment; CI/CD; testing, TDD and BDD; observability and troubleshooting; AI-assisted development; technical leadership. Chapter 12b covers React, Redux Toolkit and RTK Query as implemented in the second front end and maps them to Angular, RxJS and Signals. Questions Q1 to Q164 follow (Q145 to Q150 come from real integration defects, Q151 to Q164 cover the React front end), with the job-description traceability matrix as the closing appendix.

## Question format

Each question shows its type (conceptual or scenario), level and topic, then a short answer, a detailed answer tied to the codebase, trade-offs and failure modes, and likely follow-ups with one-line answers.

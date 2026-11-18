# 05 — System Architecture & Implementation Roadmap

**Version:** 1.0 (Baseline)  
**Target Package:** `ai.blitzpermits.permitworkflowengine`  
**Service Bounded Context:** Layered Architecture, Strategy Engine Design & Delivery Roadmap

---

## 1. Layered Architecture & Dependency Flow

`permit-workflow-engine` follows a strict **unidirectional layered architecture** with domain-isolated policy strategies. Controllers handle HTTP serialization and Bean Validation; Application Services enforce transactional boundaries, locking, and audit logging; the Policy Engine encapsulates pure business rules; and Spring Data JPA repositories manage relational persistence.

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                          HTTP / REST Presentation                       │
│   JurisdictionController (4 endpoints) · ProjectWorkflowController (8)  │
│   DTOs (Java Records) · GlobalExceptionHandler (RFC 7807 ProblemDetail) │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Transactional Application Services                   │
│         JurisdictionConfigService  ·  PermitLifecycleService            │
│    (@Transactional, PESSIMISTIC_WRITE Lock, State Mutation, Auditing)   │
└───────────────────┬────────────────────────────────────┬────────────────┘
                    │                                    │
                    ▼                                    ▼
┌───────────────────────────────────┐  ┌──────────────────────────────────┐
│     Pluggable Policy Engine       │  │     Persistence & Migrations     │
│  PolicyRegistry (Strategy Lookup) │  │  Spring Data JPA Repositories    │
│  ├── SubmissionPolicy (2)         │  │  Hibernate 7 Entities (@Version) │
│  ├── ApprovalPolicy (3)           │  │  Flyway (V1 Schema, V2 Seed)     │
│  └── ResubmissionPolicy (2)       │  │  H2 (PostgreSQL Mode) / Postgres │
└───────────────────────────────────┘  └──────────────────────────────────┘
```

### Key Architectural Boundaries
1. **Pure Policy Evaluation:** Implementations of `SubmissionPolicy`, `ApprovalPolicy`, and `ResubmissionPolicy` are stateless Spring `@Component` beans. They operate exclusively on in-memory domain objects (`Project`, `Permit`, `List<Permit>`) and return a sealed `PolicyDecision` (`Allowed` or `Denied`), performing **zero** direct database queries or HTTP calls.
2. **Dynamic Strategy Dispatch (`PolicyRegistry`):** At startup, Spring injects all strategy beans into `PolicyRegistry`, which indexes each strategy by its canonical mode key (`BUNDLED`, `MAIN_FIRST`, `BOTTOM_UP`, `TOP_DOWN`, `INDEPENDENT`, `SYNCHRONIZED`, `DECOUPLED`). Adding a new municipal rule requires only a new `@Component` implementing the relevant interface—zero changes to `PermitLifecycleService`.
3. **Immutable Configuration Pinning:** `Project.appliedConfig` holds a foreign-key reference to the exact `JurisdictionWorkflowConfig` row active when `POST /api/v1/projects` executed. Even if a municipality publishes `v2` or `v3` while a project is mid-review, `PermitLifecycleService` resolves policies from `project.getAppliedConfig()`.

---

## 2. Package-by-Package Responsibility Matrix

| Package | Responsibility & Key Classes |
| :--- | :--- |
| **`config`** | SpringDoc OpenAPI 3 metadata configuration (`OpenApiConfig`). |
| **`controller`** | REST boundary mapping HTTP verbs, path variables, `@Valid` DTOs, and status codes (`JurisdictionController`, `ProjectWorkflowController`). |
| **`dto`** | Immutable Java 21 `record` contracts separating external JSON payloads from JPA persistence entities (`CreateProjectRequest`, `PermitActionRequest`, `ProjectDetailsResponse`, `PermitResponse`, `PermitHistoryResponse`, `CreateJurisdictionRequest`, `PublishWorkflowConfigRequest`). |
| **`engine`** | Policy interfaces (`SubmissionPolicy`, `ApprovalPolicy`, `ResubmissionPolicy`) and Java 21 `sealed` `PolicyDecision` (`Allowed` \| `Denied`). |
| **`engine.strategy`** | `PolicyRegistry` plus concrete policy implementations (`BundledSubmissionPolicy`, `MainFirstSubmissionPolicy`, `BottomUpApprovalPolicy`, `TopDownApprovalPolicy`, `IndependentApprovalPolicy`, `SynchronizedResubmissionPolicy`, `DecoupledResubmissionPolicy`). |
| **`entity`** | JPA/Hibernate 7 entities mapping the 7 relational tables (`PermitType`, `Jurisdiction`, `JurisdictionWorkflowConfig`, `JurisdictionPermitMapping`, `Project`, `Permit`, `PermitStatusHistory`). |
| **`exception`** | Domain exception hierarchy (`WorkflowRuleViolationException`, `ResourceNotFoundException`) and centralized RFC 7807 `ProblemDetail` translation (`GlobalExceptionHandler`). |
| **`repository`** | Spring Data JPA interfaces including custom JPQL lock query `ProjectRepository.findByIdWithLock` (`@Lock(LockModeType.PESSIMISTIC_WRITE)`). |
| **`service`** | Transactional orchestration (`JurisdictionConfigService`, `PermitLifecycleService`). |

---

## 3. Request Execution Lifecycle (Write Path)

Every mutating workflow endpoint (e.g., `POST /api/v1/permits/{permitId}/approve`) executes through five deterministic stages:

1. **Deserialization & Bean Validation:** Jackson deserializes `PermitActionRequest`; Jakarta Validation (`@NotNull`, `@NotBlank`) rejects missing `actorId` or blank `comments` (`400 Bad Request`).
2. **Pre-Lock Guard:** `PermitLifecycleService` verifies trimmed `comments` is non-blank before touching the database connection pool.
3. **Aggregate Root Locking:** Resolves the owning `projectId` and acquires a row-level `SELECT ... FOR UPDATE` lock on `projects` via `projectRepository.findByIdWithLock(projectId)`.
4. **Structural & Policy Evaluation:**
    * Verifies the permit's current `status` permits the requested transition (`422` if invalid).
    * Looks up the pinned strategy in `PolicyRegistry` using `project.getAppliedConfig()`.
    * Pattern-matches on `PolicyDecision`: if `Denied(reason)`, throws `WorkflowRuleViolationException(reason)` (`422 Unprocessable Entity`), rolling back the transaction cleanly.
5. **Atomic Persistence & Audit Append:** Updates `Permit` status (and `Project` status if transitioning to `ACTIVE` or `COMPLETED`), writes one or more `PermitStatusHistory` rows, and commits the transaction—releasing the `Project` row lock.

---

## 4. Delivery & Enhancement Roadmap

### Phase 1: Core Engine & Multi-Tenant Foundation (Baseline v1.0 — Complete)
* 7-table Flyway schema (`V1__init_schema.sql`) and dual-city seed data (`V2__seed_jurisdictions.sql` for Tampa & Austin).
* Pluggable `PolicyRegistry` with 7 strategy implementations across Submission, Approval, and Resubmission dimensions.
* All 12 REST endpoints across `JurisdictionController` and `ProjectWorkflowController` with SpringDoc OpenAPI / Swagger UI.
* Pessimistic aggregate locking (`PESSIMISTIC_WRITE`) + optimistic entity versioning (`@Version`) + immutable audit ledger.

### Phase 2: Automated Verification & Test Coverage (Immediate Next Milestone)
* Full JUnit 5 + `MockMvc` integration test suite (`06-test-plan.md`) verifying policy matrices (`TC-POL`), synchronized sibling holds (`TC-RES`), audit chronology (`TC-AUD`), config version pinning (`TC-PIN`), multi-threaded concurrency (`TC-CON`), and database constraints (`TC-DB`).
* GitHub Actions CI pipeline executing `mvn verify` on every pull request.

### Phase 3: Production Hardening & Enterprise Extensions (v2.0 Roadmap)
* **Security & RBAC:** Integrate Spring Security OAuth2 Resource Server (JWT) to derive `actorId` and enforce role-based access (`APPLICANT` vs. `PLAN_REVIEWER` vs. `JURISDICTION_ADMIN`).
* **Custom JSON Rule Evaluator:** Extend `jurisdiction_workflow_configs.custom_rules_json` to support dynamic SLA timers, fee calculation hooks, and conditional sub-permit triggers.
* **Outbox Event Publishing:** Emit domain events (`PermitSubmittedEvent`, `CorrectionsRequestedEvent`, `ProjectCompletedEvent`) via a transactional outbox table for downstream notification and document-management services.
* **Reviewer Assignment (`IN_REVIEW`):** Add reviewer claim/assign endpoints transitioning permits from `SUBMITTED` -> `IN_REVIEW` with workload tracking.
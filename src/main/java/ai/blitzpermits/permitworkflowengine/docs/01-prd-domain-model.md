# 01 — Product Requirements & Domain Model (`permit-workflow-engine`)

**Version:** 1.0 (Baseline)  
**Target Package:** `ai.blitzpermits.permitworkflowengine.docs`  
**Service Bounded Context:** Multi-Tenant Municipal Permit Orchestration & Compliance Audit Engine

---

## 1. Executive Summary & Problem Statement

Municipal building departments process complex construction projects that span multiple interdependent trades (e.g., Structural/New Construction, Electrical, Plumbing, Mechanical/HVAC, and Solar PV). While the underlying permit entities are shared across municipalities, each jurisdiction enforces distinct regulatory sequencing rules for:
1. **Submission Ordering** (e.g., requiring all trade drawings to be submitted as one coordinated bundle vs. requiring the primary structural permit to be filed before sub-permits).
2. **Approval Sequencing** (e.g., requiring all sub-trade approvals before granting the primary building permit vs. approving the primary building permit first vs. independent parallel approvals).
3. **Corrections & Resubmission Coupling** (e.g., freezing sibling permits when one trade requires plan corrections so reviewers never approve an out-of-sync drawing set vs. allowing decoupled trade revisions).

**`permit-workflow-engine`** eliminates hardcoded conditional branching per city by externalizing municipal workflow policies into versioned, database-backed configurations evaluated by a pluggable Java 21 Policy Strategy Engine.

---

## 2. Core Functional Requirements

| Req ID | Requirement Name | Detailed Specification |
| :--- | :--- | :--- |
| **FR-01** | **Multi-Tenant Jurisdiction Registry** | Support registering multiple municipal authorities (`jurisdictions`), each maintaining an independent lifecycle of workflow configurations. |
| **FR-02** | **Immutable Policy Versioning** | Municipalities can publish new configuration versions (`version_number = 1, 2, 3...`). Publishing marks the new version `is_current = true` and retires prior versions (`is_current = false`) without mutating historical records. |
| **FR-03** | **In-Flight Project Config Pinning** | When a `Project` is created, it pins `applied_config_id` to the jurisdiction's active configuration at that moment. Subsequent policy changes published by the municipality must **never** alter the rules governing existing in-flight projects. |
| **FR-04** | **Hierarchical Permit Tree Instantiation** | Every project instantiates a two-level hierarchy: **exactly one** `MAIN` permit (`parent_permit_id = NULL`) and zero or more `SUB` permits (`parent_permit_id = mainPermit.id`), automatically including all `is_mandatory = true` sub-permits plus caller-selected optional sub-permits. |
| **FR-05** | **Runtime Policy Strategy Evaluation** | Every state transition (`submit-bundle`, `submit`, `approve`, `request-corrections`, `resubmit`) must dynamically evaluate the project's pinned `submissionMode`, `approvalMode`, and `resubmissionMode` strategies and reject violations with HTTP `422 Unprocessable Entity`. |
| **FR-06** | **Synchronized Sibling Hold & Release** | Under `SYNCHRONIZED` mode, issuing corrections on any permit automatically transitions active sibling permits to `ON_HOLD_FOR_SIBLINGS` and releases them back to `SUBMITTED` upon resubmission once no sibling remains in `CORRECTIONS_REQUIRED`. |
| **FR-07** | **Mandatory Comment & Audit Trail** | Every state transition requires a non-blank `comments` payload (`400 Bad Request` if blank) and appends an immutable row to `permit_status_history` recording `fromStatus`, `toStatus`, `policyApplied`, `actorId`, `comments`, and `createdAt`. |
| **FR-08** | **Atomic Aggregate Concurrency** | Concurrent operations on permits belonging to the same project must serialize via a row-level pessimistic write lock on the parent `Project` (`SELECT ... FOR UPDATE`), guarded secondarily by optimistic `@Version` locking on `Permit`. |

---

## 3. Non-Functional Requirements & Guardrails

* **Schema Integrity:** All persistence schemas are strictly versioned via Flyway (`V1__init_schema.sql`, `V2__seed_jurisdictions.sql`) with Hibernate configured in `ddl-auto: validate` mode.
* **Database Portability:** Runs identically against embedded H2 (`MODE=PostgreSQL`) for local development and automated test suites, and PostgreSQL 15+ in production environments.
* **Standardized Error Contracts:** All validation (`400`), missing entity (`404`), concurrency (`409`), and policy denial (`422`) failures return structured RFC 7807 `ProblemDetail` payloads via `GlobalExceptionHandler`.
* **Deferred Scope (v1.0):** External OAuth2/JWT authentication, binary PDF/CAD file storage, and asynchronous webhook/email notifications are explicitly out of scope for Baseline v1.0; caller identity is passed via `actorId` UUIDs.

---

## 4. Ubiquitous Language & Domain Entities

### 4.1 `PermitType` (Global Catalog)
Defines a standardized permit discipline shared across all municipalities (e.g., `NEW_CONSTRUCTION`, `ELECTRICAL`, `PLUMBING`, `MECHANICAL`, `SOLAR`).

### 4.2 `Jurisdiction` (Tenant / Municipality)
Represents a city, county, or permitting authority (e.g., *City of Tampa, FL*, *City of Austin, TX*) with an `isActive` status flag.

### 4.3 `JurisdictionWorkflowConfig` (Versioned Rule Snapshot)
An immutable policy snapshot belonging to a `Jurisdiction`, storing:
* `versionNumber` (monotonically increasing integer per jurisdiction)
* `isCurrent` (boolean indicating whether new projects bind to this version)
* `submissionMode` (`BUNDLED` | `MAIN_FIRST`)
* `approvalMode` (`BOTTOM_UP` | `TOP_DOWN` | `INDEPENDENT`)
* `resubmissionMode` (`SYNCHRONIZED` | `DECOUPLED`)
* `customRulesJson` (extensible JSON metadata for jurisdiction-specific SLA/fee rules)

### 4.4 `JurisdictionPermitMapping` (Template Definition)
Binds a `PermitType` to a `JurisdictionWorkflowConfig`, declaring:
* `hierarchyRole`: `MAIN` (primary structural/master permit; exactly one per config) or `SUB` (dependent trade permit).
* `isMandatory`: If `true`, the permit is automatically instantiated on every project created under this config. If `false`, the permit is instantiated only when explicitly requested in `CreateProjectRequest.selectedOptionalPermitTypeIds`.

### 4.5 `Project` (Aggregate Root)
Represents an applicant's construction filing within a single `Jurisdiction`:
* Pinned to `appliedConfig` (`JurisdictionWorkflowConfig`) at creation time.
* Tracks aggregate lifecycle status: `DRAFT` -> `ACTIVE` -> `COMPLETED` (or `CANCELLED`).
* Serves as the pessimistic locking boundary (`PESSIMISTIC_WRITE`) for all child permit mutations.

### 4.6 `Permit` (Hierarchical Work Item)
An individual permit instance belonging to a `Project`:
* Links to `parentPermit` (`NULL` for `MAIN`, references the project's `MAIN` permit for `SUB`).
* Tracks workflow `status` (`DRAFT`, `SUBMITTED`, `IN_REVIEW`, `CORRECTIONS_REQUIRED`, `ON_HOLD_FOR_SIBLINGS`, `APPROVED`), `resubmissionCycle` (starts at `0`, increments by `1` on each `/resubmit`), and optimistic lock `version`.

### 4.7 `PermitStatusHistory` (Immutable Compliance Ledger)
Append-only audit record created for every permit initialization and state transition, capturing `fromStatus`, `toStatus`, `policyApplied`, `actorId`, `comments`, and `createdAt`.
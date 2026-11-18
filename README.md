# Permit Workflow Engine (`permit-workflow-engine`)

A multi-tenant, policy-driven municipal permit orchestration microservice built with **Java 21**, **Spring Boot**, **Spring Data JPA / Hibernate 7**, and **Flyway**.

`permit-workflow-engine` models hierarchical municipal building permits (`MAIN` primary permits and dependent `SUB` trade permits), enforces jurisdiction-specific workflow policies at runtime without code changes, pins configuration versions to in-flight projects, guarantees atomic multi-permit consistency under concurrent load via pessimistic aggregate locking, and maintains an immutable compliance audit trail.

---

## Table of Contents

1. [Key Architectural Capabilities](#1-key-architectural-capabilities)
2. [Tech Stack & Project Structure](#2-tech-stack--project-structure)
3. [Domain Model & State Machine](#3-domain-model--state-machine)
4. [Policy Strategy Matrix](#4-policy-strategy-matrix)
5. [Database Schema & Flyway Migrations](#5-database-schema--flyway-migrations)
6. [Concurrency & Locking Protocol](#6-concurrency--locking-protocol)
7. [Quick Start & Local Environment](#7-quick-start--local-environment)
8. [Seeded Reference Data (H2 / Flyway V2)](#8-seeded-reference-data-h2--flyway-v2)
9. [Complete REST API Reference (12 Endpoints)](#9-complete-rest-api-reference-12-endpoints)
10. [End-to-End Swagger UI Walkthroughs](#10-end-to-end-swagger-ui-walkthroughs)
11. [Error Handling & HTTP Status Matrix](#11-error-handling--http-status-matrix)
12. [Documentation Index & Verification Roadmap](#12-documentation-index--verification-roadmap)

---

## 1. Key Architectural Capabilities

* **Configurable Policy Strategy Engine:** Decouples municipal rules into three orthogonal strategy interfaces (`SubmissionPolicy`, `ApprovalPolicy`, `ResubmissionPolicy`) resolved dynamically via `PolicyRegistry` and evaluated using Java 21 `sealed` `PolicyDecision` (`Allowed` | `Denied`) types.
* **Immutable Configuration Versioning:** Municipalities can publish new workflow versions (`v1` -> `v2`) with updated policy modes and permit mappings while existing in-flight projects remain pinned to the exact `applied_config_id` snapshot active at project creation.
* **Hierarchical Permit Tree Instantiation:** Creating a project automatically instantiates the jurisdiction's `MAIN` permit, all mandatory `SUB` permits (`is_mandatory = true`), and any caller-selected optional `SUB` permits in `DRAFT` status.
* **Synchronized Sibling Hold & Release:** Under `SYNCHRONIZED` resubmission mode, requesting corrections on any permit automatically transitions active sibling permits to `ON_HOLD_FOR_SIBLINGS` with a system audit entry.
* **Two-Tier Concurrency Control:** Serializes multi-permit invariant checks using a database row-level write lock (`PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE`) on the parent `Project` aggregate root, backed by optimistic `@Version` locking on individual `Permit` entities.
* **Immutable Audit Trail:** Every state transition enforces mandatory non-blank `comments` (`400 Bad Request` if missing) and appends a chronological record to `permit_status_history` capturing `fromStatus`, `toStatus`, `policyApplied`, `actorId`, `comments`, and `createdAt`.

---

## 2. Tech Stack & Project Structure

| Layer | Technology |
| :--- | :--- |
| **Language & Runtime** | Java 21 (Records, Pattern Matching `switch`, Sealed Interfaces) |
| **Framework** | Spring Boot (Web MVC, Data JPA, Validation, Actuator) |
| **ORM & Persistence** | Hibernate 7 + HikariCP |
| **Schema Migrations** | Flyway (`V1__init_schema.sql`, `V2__seed_jurisdictions.sql`) |
| **Databases** | H2 In-Memory (`MODE=PostgreSQL`) for Local/Test; PostgreSQL for Production |
| **API Documentation** | SpringDoc OpenAPI 3 / Swagger UI (`/swagger-ui.html`, `/v3/api-docs`) |
| **Boilerplate Reduction** | Lombok (`@Builder`, `@Getter`, `@Setter`, `@RequiredArgsConstructor`) |

```text
permit-workflow-engine/
├── pom.xml
├── src/main/java/ai/blitzpermits/permitworkflowengine/
│   ├── PermitWorkflowEngineApplication.java
│   ├── config/
│   │   └── OpenApiConfig.java
│   ├── controller/
│   │   ├── JurisdictionController.java         # 4 admin/config endpoints (/api/v1/jurisdictions)
│   │   └── ProjectWorkflowController.java      # 8 runtime workflow endpoints (/api/v1/projects & /permits)
│   ├── dto/
│   │   ├── CreateJurisdictionRequest.java
│   │   ├── PublishWorkflowConfigRequest.java
│   │   ├── CreateProjectRequest.java
│   │   ├── PermitActionRequest.java
│   │   ├── ProjectDetailsResponse.java
│   │   ├── PermitResponse.java
│   │   └── PermitHistoryResponse.java
│   ├── engine/
│   │   ├── PolicyDecision.java                 # Sealed interface: Allowed | Denied(reason)
│   │   ├── SubmissionPolicy.java
│   │   ├── ApprovalPolicy.java
│   │   ├── ResubmissionPolicy.java
│   │   └── strategy/
│   │       ├── PolicyRegistry.java
│   │       ├── BundledSubmissionPolicy.java
│   │       ├── MainFirstSubmissionPolicy.java
│   │       ├── BottomUpApprovalPolicy.java
│   │       ├── TopDownApprovalPolicy.java
│   │       ├── IndependentApprovalPolicy.java
│   │       ├── SynchronizedResubmissionPolicy.java
│   │       └── DecoupledResubmissionPolicy.java
│   ├── entity/
│   │   ├── PermitType.java
│   │   ├── Jurisdiction.java
│   │   ├── JurisdictionWorkflowConfig.java
│   │   ├── JurisdictionPermitMapping.java
│   │   ├── Project.java
│   │   ├── Permit.java
│   │   └── PermitStatusHistory.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java         # RFC 7807 ProblemDetail mapping (400, 404, 409, 422)
│   │   ├── ResourceNotFoundException.java
│   │   └── WorkflowRuleViolationException.java
│   ├── repository/
│   │   ├── PermitTypeRepository.java
│   │   ├── JurisdictionRepository.java
│   │   ├── JurisdictionWorkflowConfigRepository.java
│   │   ├── JurisdictionPermitMappingRepository.java
│   │   ├── ProjectRepository.java              # Includes @Lock(PESSIMISTIC_WRITE) findByIdWithLock
│   │   ├── PermitRepository.java
│   │   └── PermitStatusHistoryRepository.java
│   ├── service/
│   │   ├── JurisdictionConfigService.java
│   │   └── PermitLifecycleService.java
│   └── docs/
│       ├── 01-prd-domain-model.md
│       ├── 02-state-machine-and-policies.md
│       ├── 03-database-and-concurrency.md
│       ├── 04-openapi-contract.md
│       ├── 05-architecture-and-roadmap.md
│       └── 06-test-plan.md
└── src/main/resources/
    ├── application.yaml
    └── db/migration/
        ├── V1__init_schema.sql
        └── V2__seed_jurisdictions.sql
```

---

## 3. Domain Model & State Machine

### 3.1 Project Lifecycle States (`projects.status`)
* **`DRAFT`**: Project created; all instantiated permits are in `DRAFT`.
* **`ACTIVE`**: At least one permit (or the entire bundle) has been submitted for municipal review.
* **`COMPLETED`**: Every permit (`MAIN` and all `SUB` permits) in the project has reached `APPROVED`.
* **`CANCELLED`**: Project terminated prior to completion.

### 3.2 Permit Lifecycle States (`permits.status`)
* **`DRAFT`**: Initial state upon project creation (`resubmissionCycle = 0`).
* **`SUBMITTED`**: Submitted by applicant and awaiting or undergoing municipal plan review.
* **`IN_REVIEW`**: Reserved state for active reviewer assignment.
* **`CORRECTIONS_REQUIRED`**: Reviewer issued plan-check comments; applicant must revise and resubmit.
* **`ON_HOLD_FOR_SIBLINGS`**: Automatically applied to active sibling permits when another permit in the project enters `CORRECTIONS_REQUIRED` under `SYNCHRONIZED` mode.
* **`APPROVED`**: Terminal approval state for an individual permit.

```text
  [Project Created]
         │
         ▼
      ┌──────┐   submit-bundle / submit    ┌───────────┐
      │DRAFT │ ──────────────────────────► │ SUBMITTED │ ◄──────────────┐
      └──────┘                             └─────┬─────┘                │
                                                 │                      │
                          ┌──────────────────────┼────────────────┐     │
                          │ approve              │ request-       │     │ resubmit
                          ▼                      │ corrections    │     │ (cycle + 1)
                    ┌──────────┐                 ▼                │     │
                    │ APPROVED │     ┌──────────────────────┐     │     │
                    └──────────┘     │ CORRECTIONS_REQUIRED ├─────┼─────┘
                                     └───────────┬──────────┘     │
                                                 │ (if SYNCHRONIZED)
                                                 ▼                │
                                     ┌───────────────────────┐    │
                                     │ ON_HOLD_FOR_SIBLINGS  │────┘
                                     └───────────────────────┘
```

---

## 4. Policy Strategy Matrix

Every `JurisdictionWorkflowConfig` combines three independent policy strategies:

### 4.1 Submission Policies (`submissionMode`)
| Mode | Strategy Class | Enforcement Rule |
| :--- | :--- | :--- |
| **`BUNDLED`** | `BundledSubmissionPolicy` | Blocks single-permit submission (`POST /permits/{id}/submit` returns `422`). Applicant must submit all `DRAFT` permits together via `POST /projects/{id}/submit-bundle`. |
| **`MAIN_FIRST`** | `MainFirstSubmissionPolicy` | Allows `MAIN` permit submission anytime; blocks `SUB` permit submission (`422`) while `MAIN` is still in `DRAFT`. Also permits batch submission via `submit-bundle`. |

### 4.2 Approval Policies (`approvalMode`)
| Mode | Strategy Class | Enforcement Rule |
| :--- | :--- | :--- |
| **`BOTTOM_UP`** | `BottomUpApprovalPolicy` | `SUB` permits can be approved independently, but `MAIN` permit approval is blocked (`422`) until **all** `SUB` permits in the project are `APPROVED`. |
| **`TOP_DOWN`** | `TopDownApprovalPolicy` | `MAIN` permit can be approved first, but `SUB` permit approval is blocked (`422`) until the `MAIN` permit is `APPROVED`. |
| **`INDEPENDENT`** | `IndependentApprovalPolicy` | `MAIN` and `SUB` permits can be approved in any order without cross-permit blocking. |

### 4.3 Resubmission Policies (`resubmissionMode`)
| Mode | Strategy Class | Enforcement Rule |
| :--- | :--- | :--- |
| **`SYNCHRONIZED`** | `SynchronizedResubmissionPolicy` | When `request-corrections` is called on any permit, active sibling permits (not in `DRAFT`, `CORRECTIONS_REQUIRED`, or `ON_HOLD_FOR_SIBLINGS`) automatically move to `ON_HOLD_FOR_SIBLINGS` with a system audit log. Calling `resubmit` increments `resubmissionCycle` and returns the permit to `SUBMITTED`. |
| **`DECOUPLED`** | `DecoupledResubmissionPolicy` | Corrections and resubmissions affect **only** the target permit; sibling permits remain unaffected in their current states. |

---

## 5. Database Schema & Flyway Migrations

Flyway executes automatically on startup (`spring.flyway.enabled=true`) with Hibernate schema validation (`ddl-auto: validate`).

| Migration | Description |
| :--- | :--- |
| **`V1__init_schema.sql`** | Creates all 7 normalized tables, foreign keys (`ON DELETE CASCADE` / `RESTRICT`), `UNIQUE` constraints, `CHECK` constraints, and indexes. |
| **`V2__seed_jurisdictions.sql`** | Seeds 5 global `permit_types` and 2 complete jurisdiction configurations (**City of Tampa** and **City of Austin**). |

### Core Tables
1. **`permit_types`**: Global catalog (`id`, `code` UNIQUE, `name`, `description`, `is_active`, `created_at`).
2. **`jurisdictions`**: Municipalities (`id`, `name`, `state_code`, `is_active`, `created_at`).
3. **`jurisdiction_workflow_configs`**: Versioned policy rules (`id`, `jurisdiction_id` FK, `version_number`, `is_current`, `submission_mode`, `approval_mode`, `resubmission_mode`, `custom_rules_json`, `created_at`). Unique on `(jurisdiction_id, version_number)`.
4. **`jurisdiction_permit_mappings`**: Maps permit types to a config version (`id`, `config_id` FK, `permit_type_id` FK, `hierarchy_role` [`MAIN` \| `SUB`], `is_mandatory`). Unique on `(config_id, permit_type_id)`.
5. **`projects`**: Applicant project aggregate root (`id`, `applicant_id`, `jurisdiction_id` FK, `applied_config_id` FK, `status`, `created_at`).
6. **`permits`**: Instantiated project permits (`id`, `project_id` FK, `parent_permit_id` self-FK, `permit_type_id` FK, `status`, `resubmission_cycle`, `version` optimistic lock, `updated_at`). Unique on `(project_id, permit_type_id)`.
7. **`permit_status_history`**: Immutable audit log (`id`, `permit_id` FK, `from_status`, `to_status`, `policy_applied`, `actor_id`, `comments`, `created_at`).

---

## 6. Concurrency & Locking Protocol

Every mutating workflow method in `PermitLifecycleService` (`submitProjectBundle`, `submitSinglePermit`, `approvePermit`, `requestCorrections`, `resubmitPermit`) executes within a single `@Transactional` boundary:

1. **Pre-Lock Input Validation:** Validates non-blank `comments` first (`IllegalArgumentException` -> `400 Bad Request`) before acquiring database locks.
2. **Aggregate Root Row Lock:** Acquires a `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) lock on the parent `Project` via `projectRepository.findByIdWithLock(projectId)`. Concurrent requests targeting sibling permits in the same project block cleanly at the database row level until the active transaction commits.
3. **Invariant Evaluation & Mutation:** Evaluates the pinned `PolicyRegistry` strategy against the locked snapshot of all project permits, updates statuses, and appends `PermitStatusHistory` rows.
4. **Optimistic Guard:** `Permit.version` (`@Version`) provides a secondary optimistic concurrency check; any `OptimisticLockingFailureException` maps to HTTP `409 Conflict` via `GlobalExceptionHandler`.

---

## 7. Quick Start & Local Environment

### Prerequisites
* **JDK 21**
* **Maven 3.9+** (or included `./mvnw` / `mvnw.cmd`)

### Run the Application
```bash
mvn spring-boot:run
```

### Key Local URLs
* **Swagger UI (Interactive API Console):** `http://localhost:8080/swagger-ui.html`
* **OpenAPI 3 JSON Specification:** `http://localhost:8080/v3/api-docs`
* **H2 Database Console:** `http://localhost:8080/h2-console`
    * **JDBC URL:** `jdbc:h2:mem:blitzpermits`
    * **Username:** `sa`
    * **Password:** *(leave empty)*
* **Actuator Health Check:** `http://localhost:8080/actuator/health`

---

## 8. Seeded Reference Data (H2 / Flyway V2)

### 8.1 Seeded Jurisdictions & Policy Matrices
| City | `jurisdictionId` | `submissionMode` | `approvalMode` | `resubmissionMode` | Permit Mappings |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **City of Tampa, FL** | `11111111-1111-1111-1111-111111111111` | `BUNDLED` | `BOTTOM_UP` | `SYNCHRONIZED` | `NEW_CONSTRUCTION` (`MAIN`, Mandatory)<br>`PLUMBING` (`SUB`, Mandatory)<br>`ELECTRICAL` (`SUB`, Optional)<br>`SOLAR` (`SUB`, Optional) |
| **City of Austin, TX** | `33333333-3333-3333-3333-333333333333` | `MAIN_FIRST` | `TOP_DOWN` | `DECOUPLED` | `ELECTRICAL` (`MAIN`, Mandatory)<br>`MECHANICAL` (`SUB`, Optional)<br>`SOLAR` (`SUB`, Optional) |

### 8.2 Seeded Global Permit Types (`permit_types`)
| Code | Name | `permitTypeId` |
| :--- | :--- | :--- |
| `NEW_CONSTRUCTION` | New Building Construction | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1` |
| `ELECTRICAL` | Electrical Permit | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2` |
| `PLUMBING` | Plumbing Permit | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3` |
| `MECHANICAL` | Mechanical / HVAC Permit | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4` |
| `SOLAR` | Solar PV Installation Permit | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5` |

---

## 9. Complete REST API Reference (12 Endpoints)

### Part I: Jurisdiction & Configuration Endpoints (`JurisdictionController`)

| # | Method | Path | Summary | Status |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `POST` | `/api/v1/jurisdictions` | Register a new active municipality | `201 Created` |
| **2** | `GET` | `/api/v1/jurisdictions` | List all active jurisdictions | `200 OK` |
| **3** | `POST` | `/api/v1/jurisdictions/{jurisdictionId}/config` | Publish a new active workflow policy version (`v1`, `v2`, ...) | `201 Created` |
| **4** | `GET` | `/api/v1/jurisdictions/{jurisdictionId}/config` | Get active workflow modes and permit mappings | `200 OK` |

#### Endpoint 1: Create Jurisdiction (`POST /api/v1/jurisdictions`)
* **Validation:** `name` non-blank; `stateCode` exactly 2 characters (normalized to uppercase).
* **Request (`CreateJurisdictionRequest`):**
  ```json
  {
    "name": "City of Miami",
    "stateCode": "FL"
  }
  ```
* **Response (`201 Created` — `JurisdictionSummary`):**
  ```json
  {
    "id": "55555555-5555-5555-5555-555555555555",
    "name": "City of Miami",
    "stateCode": "FL"
  }
  ```

#### Endpoint 2: List Active Jurisdictions (`GET /api/v1/jurisdictions`)
* **Response (`200 OK`):** Array of `JurisdictionSummary` objects.

#### Endpoint 3: Publish Workflow Configuration Version (`POST /api/v1/jurisdictions/{jurisdictionId}/config`)
* **Validation:** Must define **exactly one** mapping with `"hierarchyRole": "MAIN"`; all `permitTypeCode` values must exist in `permit_types`; policy mode strings must match registered strategies (`BUNDLED`/`MAIN_FIRST`, `BOTTOM_UP`/`TOP_DOWN`/`INDEPENDENT`, `SYNCHRONIZED`/`DECOUPLED`). Automatically marks any prior active version as `is_current = false` and increments `versionNumber`.
* **Request (`PublishWorkflowConfigRequest`):**
  ```json
  {
    "submissionMode": "MAIN_FIRST",
    "approvalMode": "INDEPENDENT",
    "resubmissionMode": "DECOUPLED",
    "mappings": [
      { "permitTypeCode": "NEW_CONSTRUCTION", "hierarchyRole": "MAIN", "isMandatory": true },
      { "permitTypeCode": "ELECTRICAL", "hierarchyRole": "SUB", "isMandatory": true },
      { "permitTypeCode": "SOLAR", "hierarchyRole": "SUB", "isMandatory": false }
    ]
  }
  ```
* **Response (`201 Created` — `ActiveConfigResponse`):**
  ```json
  {
    "configId": "66666666-6666-6666-6666-666666666666",
    "versionNumber": 1,
    "submissionMode": "MAIN_FIRST",
    "approvalMode": "INDEPENDENT",
    "resubmissionMode": "DECOUPLED",
    "permitMappings": [
      {
        "permitTypeId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1",
        "permitTypeCode": "NEW_CONSTRUCTION",
        "permitTypeName": "New Building Construction",
        "hierarchyRole": "MAIN",
        "isMandatory": true
      }
    ]
  }
  ```

#### Endpoint 4: Get Active Workflow Configuration (`GET /api/v1/jurisdictions/{jurisdictionId}/config`)
* **Response (`200 OK`):** Returns `ActiveConfigResponse` for the current version (`404 Not Found` if no active config exists).

---

### Part II: Project & Permit Lifecycle Endpoints (`ProjectWorkflowController`)

| # | Method | Path | Summary | Status |
| :--- | :--- | :--- | :--- | :--- |
| **5** | `POST` | `/api/v1/projects` | Create project & instantiate `MAIN` + `SUB` permits in `DRAFT` | `201 Created` |
| **6** | `GET` | `/api/v1/projects/{projectId}` | Fetch project details, pinned policy modes, and permit tree | `200 OK` |
| **7** | `POST` | `/api/v1/projects/{projectId}/submit-bundle` | Atomically submit all `DRAFT` permits in project | `200 OK` |
| **8** | `POST` | `/api/v1/permits/{permitId}/submit` | Submit an individual `DRAFT` permit | `200 OK` |
| **9** | `POST` | `/api/v1/permits/{permitId}/approve` | Approve a `SUBMITTED` permit under `ApprovalPolicy` | `200 OK` |
| **10** | `POST` | `/api/v1/permits/{permitId}/request-corrections` | Request corrections (and auto-hold siblings if `SYNCHRONIZED`) | `200 OK` |
| **11** | `POST` | `/api/v1/permits/{permitId}/resubmit` | Resubmit after corrections (`resubmissionCycle + 1`) | `200 OK` |
| **12** | `GET` | `/api/v1/permits/{permitId}/history` | Retrieve chronological audit history (`createdAt ASC`) | `200 OK` |

#### Endpoint 5: Create Project (`POST /api/v1/projects`)
* **Request (`CreateProjectRequest`):**
  ```json
  {
    "applicantId": "99999999-9999-9999-9999-000000000001",
    "jurisdictionId": "11111111-1111-1111-1111-111111111111",
    "selectedOptionalPermitTypeIds": [
      "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
    ]
  }
  ```
* **Response (`201 Created` — `ProjectDetailsResponse`):**
  ```json
  {
    "id": "77777777-7777-7777-7777-777777777777",
    "applicantId": "99999999-9999-9999-9999-000000000001",
    "jurisdictionId": "11111111-1111-1111-1111-111111111111",
    "jurisdictionName": "City of Tampa",
    "status": "DRAFT",
    "appliedPolicy": {
      "versionNumber": 1,
      "submissionMode": "BUNDLED",
      "approvalMode": "BOTTOM_UP",
      "resubmissionMode": "SYNCHRONIZED"
    },
    "permits": [
      {
        "id": "88888888-8888-8888-8888-000000000001",
        "parentPermitId": null,
        "permitTypeCode": "NEW_CONSTRUCTION",
        "permitTypeName": "New Building Construction",
        "status": "DRAFT",
        "resubmissionCycle": 0,
        "updatedAt": "2026-11-18T12:00:00Z"
      }
    ],
    "createdAt": "2026-11-18T12:00:00Z"
  }
  ```

#### Endpoints 7–11: Transition Action Request Body (`PermitActionRequest`)
All five state-transition endpoints accept the exact same JSON body (`comments` is mandatory and non-blank):
```json
{
  "actorId": "99999999-9999-9999-9999-000000000002",
  "comments": "Detailed review or submission note required for audit trail."
}
```
* `POST /api/v1/projects/{projectId}/submit-bundle` -> returns `ProjectDetailsResponse`
* `POST /api/v1/permits/{permitId}/submit` -> returns `PermitResponse`
* `POST /api/v1/permits/{permitId}/approve` -> returns `PermitResponse`
* `POST /api/v1/permits/{permitId}/request-corrections` -> returns `List<PermitResponse>` (target permit + any siblings moved to `ON_HOLD_FOR_SIBLINGS`)
* `POST /api/v1/permits/{permitId}/resubmit` -> returns `PermitResponse` (`status: "SUBMITTED"`, `resubmissionCycle` incremented by 1)

#### Endpoint 12: Get Permit Audit History (`GET /api/v1/permits/{permitId}/history`)
* **Response (`200 OK` — `List<PermitHistoryResponse>`):**
  ```json
  [
    {
      "id": "12121212-1212-1212-1212-121212121212",
      "fromStatus": null,
      "toStatus": "DRAFT",
      "policyApplied": "PROJECT_INIT",
      "actorId": "99999999-9999-9999-9999-000000000001",
      "comments": "Project initialized in DRAFT status.",
      "createdAt": "2026-11-18T12:00:00Z"
    }
  ]
  ```

---

## 10. End-to-End Swagger UI Walkthroughs

Open `http://localhost:8080/swagger-ui.html` after starting the service.

### Scenario A: City of Tampa (`BUNDLED` + `BOTTOM_UP` + `SYNCHRONIZED`)
1. **Create Project (`POST /api/v1/projects`):** Use `jurisdictionId = 11111111-1111-1111-1111-111111111111` and `selectedOptionalPermitTypeIds = ["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"]`. Note the returned `projectId`, `mainPermitId` (`NEW_CONSTRUCTION`), `plumbingPermitId` (`PLUMBING`, auto-added because mandatory), and `electricalPermitId` (`ELECTRICAL`).
2. **Verify `BUNDLED` Rejection (`POST /api/v1/permits/{mainPermitId}/submit`):** Returns **`422 Unprocessable Entity`** because single-permit submission is disallowed in Tampa.
3. **Submit Bundle (`POST /api/v1/projects/{projectId}/submit-bundle`):** Returns **`200 OK`**; project moves to `ACTIVE` and all 3 permits move to `SUBMITTED`.
4. **Verify `BOTTOM_UP` Rejection (`POST /api/v1/permits/{mainPermitId}/approve`):** Returns **`422 Unprocessable Entity`** because `PLUMBING` and `ELECTRICAL` (`SUB`) are not yet approved.
5. **Request Corrections (`POST /api/v1/permits/{electricalPermitId}/request-corrections`):** Returns **`200 OK`**; `ELECTRICAL` becomes `CORRECTIONS_REQUIRED`, and both `NEW_CONSTRUCTION` and `PLUMBING` automatically transition to `ON_HOLD_FOR_SIBLINGS`.
6. **Resubmit Revision (`POST /api/v1/permits/{electricalPermitId}/resubmit`):** Returns **`200 OK`**; `ELECTRICAL` returns to `SUBMITTED` with `resubmissionCycle: 1`.
7. **Complete Bottom-Up Approval:** Approve `ELECTRICAL` (`200 OK`), approve `PLUMBING` (`200 OK`), and finally approve `NEW_CONSTRUCTION` (`200 OK`). Call `GET /api/v1/projects/{projectId}` to confirm project `status` is now **`COMPLETED`**.
8. **Audit Trail (`GET /api/v1/permits/{electricalPermitId}/history`):** Verify all 5 chronological transitions (`null -> DRAFT -> SUBMITTED -> CORRECTIONS_REQUIRED -> SUBMITTED -> APPROVED`).

### Scenario B: City of Austin (`MAIN_FIRST` + `TOP_DOWN` + `DECOUPLED`)
1. **Create Project (`POST /api/v1/projects`):** Use `jurisdictionId = 33333333-3333-3333-3333-333333333333` and `selectedOptionalPermitTypeIds = ["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5"]` (`SOLAR`). `ELECTRICAL` is instantiated as `MAIN` and `SOLAR` as `SUB`.
2. **Test `MAIN_FIRST`:** Calling `POST /api/v1/permits/{solarPermitId}/submit` while `ELECTRICAL` is `DRAFT` fails with **`422`**. Submitting `ELECTRICAL` first (`200 OK`) unlocks `SOLAR` submission (`200 OK`).
3. **Test `TOP_DOWN`:** Calling `POST /api/v1/permits/{solarPermitId}/approve` while `ELECTRICAL` is `SUBMITTED` fails with **`422`**. Approving `ELECTRICAL` first (`200 OK`) unlocks `SOLAR` approval (`200 OK`).
4. **Test `DECOUPLED`:** Requesting corrections on a `SUBMITTED` `SUB` permit moves **only** that permit to `CORRECTIONS_REQUIRED` without placing `MAIN` on `ON_HOLD_FOR_SIBLINGS`.

---

## 11. Error Handling & HTTP Status Matrix

`GlobalExceptionHandler` translates exceptions into RFC 7807 `ProblemDetail` JSON responses:

| HTTP Status | Exception | Trigger Condition |
| :--- | :--- | :--- |
| **`400 Bad Request`** | `IllegalArgumentException` | Missing/blank `comments` on transition endpoints, unknown `projectId` / `permitId` / `jurisdictionId`, invalid state code length, or publishing a config without exactly one `MAIN` permit. |
| **`404 Not Found`** | `ResourceNotFoundException` | Requesting `GET /api/v1/jurisdictions/{id}/config` for a jurisdiction with no active workflow configuration. |
| **`409 Conflict`** | `OptimisticLockingFailureException` | Concurrent modification detected on a permit entity version (`@Version`). |
| **`422 Unprocessable Entity`** | `WorkflowRuleViolationException` | Valid request blocked by jurisdiction policy (`BUNDLED` single submit, `MAIN_FIRST` early sub submit, `BOTTOM_UP` early main approval, `TOP_DOWN` early sub approval, approving an already `APPROVED` permit, or `submit-bundle` with no `DRAFT` permits). |

---

## 12. Documentation Index & Verification Roadmap

Detailed engineering specifications live under `src/main/java/ai/blitzpermits/permitworkflowengine/docs/`:
* `01-prd-domain-model.md` — Product requirements & domain entities
* `02-state-machine-and-policies.md` — State transition rules & policy definitions
* `03-database-and-concurrency.md` — 7-table schema, Flyway chain & `PESSIMISTIC_WRITE` protocol
* `04-openapi-contract.md` — REST API & error contract specification
* `05-architecture-and-roadmap.md` — System architecture & milestone roadmap
* `06-test-plan.md` — JUnit 5 + `MockMvc` acceptance test matrix (`TC-POL`, `TC-RES`, `TC-AUD`, `TC-PIN`, `TC-CON`, `TC-DB`)
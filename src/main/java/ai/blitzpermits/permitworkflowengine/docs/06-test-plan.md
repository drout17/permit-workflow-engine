# 06 — Verification & JUnit 5 Integration Test Plan

**Version:** 1.0 (Baseline)  
**Target Package:** `src/test/java/ai/blitzpermits/permitworkflowengine/`  
**Test Stack:** JUnit 5 (Jupiter) · Spring Boot Test (`@SpringBootTest`, `@AutoConfigureMockMvc`) · AssertJ · Flyway + H2 (`MODE=PostgreSQL`)

---

## 1. Testing Strategy & Environment Isolation

To validate end-to-end regulatory enforcement, transactional locking, and schema constraints without mocking away SQL semantics:

* **Full Spring Context + Real Flyway Migrations:** Integration tests boot the full Spring Boot context against an isolated embedded H2 database (`MODE=PostgreSQL`), automatically executing `V1__init_schema.sql` and `V2__seed_jurisdictions.sql` with Hibernate `ddl-auto: validate`.
* **HTTP Boundary Verification (`MockMvc`):** All workflow scenarios execute through the REST controllers (`JurisdictionController` and `ProjectWorkflowController`) using `MockMvc` to verify JSON serialization, Bean Validation (`@Valid`), HTTP status codes (`200`, `201`, `400`, `404`, `409`, `422`), and RFC 7807 `ProblemDetail` payloads.
* **Multi-Threaded Concurrency Verification (`ExecutorService` + `CountDownLatch`):** Concurrent race-condition tests intentionally bypass `@Transactional` test rollback on the outer test method so worker threads contend on real committed database rows and `SELECT ... FOR UPDATE` locks.

---

## 2. Acceptance Test Matrix (14 Core Test Cases)

### 2.1 Policy Strategy Enforcement (`TC-POL`)

| Test ID | Policy Matrix | Scenario & Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **`TC-POL-01`** | `BUNDLED` (Tampa) | Create Tampa project (`NEW_CONSTRUCTION` + `PLUMBING`), then call `POST /permits/{mainId}/submit` individually. | Returns **`422 Unprocessable Entity`** (`WorkflowRuleViolationException`); permit remains `DRAFT`. |
| **`TC-POL-02`** | `BUNDLED` (Tampa) | Call `POST /projects/{projectId}/submit-bundle` on the Tampa project. | Returns **`200 OK`**; `Project.status` becomes `ACTIVE` and all `DRAFT` permits transition to `SUBMITTED`. |
| **`TC-POL-03`** | `MAIN_FIRST` (Austin) | Create Austin project (`ELECTRICAL` `MAIN` + `SOLAR` `SUB`). Attempt `POST /permits/{solarId}/submit` while `ELECTRICAL` is still `DRAFT`. | Returns **`422 Unprocessable Entity`**; `SOLAR` remains `DRAFT`. |
| **`TC-POL-04`** | `MAIN_FIRST` (Austin) | Submit `ELECTRICAL` (`MAIN`) first (`200 OK`), then submit `SOLAR` (`SUB`). | Both calls return **`200 OK`**; both permits reach `SUBMITTED`. |
| **`TC-POL-05`** | `BOTTOM_UP` (Tampa) | Bundle-submit Tampa project. Attempt `POST /permits/{mainId}/approve` before `PLUMBING` (`SUB`) is approved; then approve `PLUMBING` first, followed by `MAIN`. | Early `MAIN` approval returns **`422`**; approving `PLUMBING` then `MAIN` succeeds (**`200 OK`**) and marks project **`COMPLETED`**. |
| **`TC-POL-06`** | `TOP_DOWN` (Austin) | Submit Austin `MAIN` + `SUB`. Attempt `POST /permits/{solarId}/approve` before `ELECTRICAL` (`MAIN`) is approved; then approve `ELECTRICAL` first, followed by `SOLAR`. | Early `SUB` approval returns **`422`**; approving `MAIN` then `SUB` succeeds (**`200 OK`**) and marks project **`COMPLETED`**. |

### 2.2 Resubmission & Sibling Hold Coupling (`TC-RES`)

| Test ID | Policy Matrix | Scenario & Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **`TC-RES-01`** | `SYNCHRONIZED` (Tampa) | Bundle-submit Tampa project (`MAIN`, `PLUMBING`, `ELECTRICAL`). Call `POST /permits/{electricalId}/request-corrections`. | Returns **`200 OK`**; `ELECTRICAL` becomes `CORRECTIONS_REQUIRED`; `MAIN` and `PLUMBING` automatically transition to `ON_HOLD_FOR_SIBLINGS`. |
| **`TC-RES-02`** | `SYNCHRONIZED` (Tampa) | Continuing `TC-RES-01`, attempt `POST /permits/{plumbingId}/approve` while `PLUMBING` is `ON_HOLD_FOR_SIBLINGS`; then call `POST /permits/{electricalId}/resubmit`. | Approval while on hold fails with **`422`**; `/resubmit` returns **`200 OK`**, sets `ELECTRICAL` to `SUBMITTED` with `resubmissionCycle == 1`, and releases `MAIN` and `PLUMBING` back to `SUBMITTED`. |
| **`TC-RES-03`** | `DECOUPLED` (Austin) | Submit Austin `MAIN` and `SUB`. Call `POST /permits/{solarId}/request-corrections`. | Returns **`200 OK`** with **only** `SOLAR` in `CORRECTIONS_REQUIRED`; `MAIN` stays `SUBMITTED` (never placed on hold) and can be approved immediately. |

### 2.3 Immutable Audit Trail & Input Guards (`TC-AUD`)

| Test ID | Verification Focus | Scenario & Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **`TC-AUD-01`** | Mandatory Comments (`400`) | Invoke `submit-bundle`, `submit`, `approve`, `request-corrections`, and `resubmit` with `comments: ""` and `comments: "   "`. | Every request is rejected with **`400 Bad Request`** prior to state mutation or audit insertion. |
| **`TC-AUD-02`** | Chronological Audit History | Execute full lifecycle (`create -> submit-bundle -> request-corrections -> resubmit -> approve`) and call `GET /permits/{permitId}/history`. | Returns **`200 OK`** with entries ordered `createdAt ASC`, verifying exact `fromStatus`, `toStatus`, `policyApplied`, `actorId`, and trimmed `comments`. |

### 2.4 Configuration Versioning & In-Flight Pinning (`TC-PIN`)

| Test ID | Verification Focus | Scenario & Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **`TC-PIN-01`** | Config Pinning Across Versions | Create Project P1 under Tampa `v1` (`BUNDLED` / `BOTTOM_UP` / `SYNCHRONIZED`). Publish Tampa `v2` (`MAIN_FIRST` / `INDEPENDENT` / `DECOUPLED`). Create Project P2. Verify P1 still enforces `v1` (`BUNDLED`) while P2 enforces `v2` (`MAIN_FIRST`). | P1 rejects single-permit `/submit` (**`422`**) and reports `appliedPolicy.versionNumber == 1`; P2 allows single-permit `/submit` (**`200`**) and reports `versionNumber == 2`. |

### 2.5 Concurrency & Database Integrity (`TC-CON` & `TC-DB`)

| Test ID | Verification Focus | Scenario & Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **`TC-CON-01`** | Pessimistic Aggregate Locking | Under Tampa (`BOTTOM_UP`), launch two concurrent threads (`CountDownLatch`) on the same project: Thread A calls `approve` on `SUB` (`PLUMBING`) while Thread B simultaneously calls `approve` on `MAIN`. | `PESSIMISTIC_WRITE` lock on `Project` serializes execution cleanly with zero deadlocks or lost updates; `MAIN` approval either fails deterministically (`422` if it runs first) or succeeds (`200` if it runs second). |
| **`TC-DB-01`** | Schema & Config Invariants | Attempt to publish a jurisdiction config (`POST /jurisdictions/{id}/config`) with zero `MAIN` permits, two `MAIN` permits, or duplicate permit mappings. | Rejected with **`400 Bad Request`**; database uniqueness and check constraints remain intact. |

---

## 3. Recommended Test Class Organization

```text
src/test/java/ai/blitzpermits/permitworkflowengine/
├── PermitWorkflowEngineApplicationTests.java       # Smoke test: context + Flyway V1/V2 validation
├── WorkflowPolicyIntegrationTest.java              # TC-POL-01..06, TC-RES-01..03, TC-AUD-01..02
├── JurisdictionConfigPinningTest.java              # TC-PIN-01, TC-DB-01
└── ProjectConcurrencyIntegrationTest.java          # TC-CON-01 (multi-threaded locking verification)
```

---

## 4. Test Execution Commands

```bash
# Run all unit and integration tests
mvn test

# Run a single integration test class
mvn -Dtest=WorkflowPolicyIntegrationTest test
```
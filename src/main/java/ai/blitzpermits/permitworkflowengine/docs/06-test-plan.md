# 06 — Verification & Acceptance Test Plan

**Service:** `permit-workflow-engine`  
**Test Framework:** JUnit 5, Spring Boot Test (`@SpringBootTest`), MockMvc / TestRestTemplate  
**Database:** H2 (In-Memory with Flyway) & PostgreSQL (Integration)  
**Status:** Baseline v1.0

---

## 1. Test Strategy & Scope

Verification validates four core guarantees of `permit-workflow-engine`:
1. **Policy Strategy Enforcement:** All combinations of `SubmissionPolicy`, `ApprovalPolicy`, and `ResubmissionPolicy` permit valid transitions (`200 OK`) and block policy violations (`422 Unprocessable Entity`).
2. **Audit Trail & Mandatory Comments:** Every state transition rejects null/blank comments (`400 Bad Request`) and writes immutable, chronologically ordered `permit_status_history` records.
3. **Config Version Isolation:** Updating a jurisdiction's active `JurisdictionWorkflowConfig` never alters the rules applied to pre-existing in-flight projects.
4. **Concurrency Serialization:** Pessimistic row-level locking (`SELECT ... FOR UPDATE`) on the parent `Project` prevents race conditions across concurrent multi-permit transitions.

---

## 2. Policy Matrix Test Cases

| Test ID | Policy Configuration | Action Sequence | Expected Result |
| :--- | :--- | :--- | :--- |
| **`TC-POL-01`** | `BUNDLED` + `BOTTOM_UP` + `SYNCHRONIZED` | Call `POST /permits/{id}/submit` on a single `DRAFT` permit. | **`422 Unprocessable Entity`** — Individual submission blocked under `BUNDLED` policy. |
| **`TC-POL-02`** | `BUNDLED` + `BOTTOM_UP` + `SYNCHRONIZED` | Call `POST /projects/{id}/submit-bundle` → approve `MAIN` while `SUB` is `SUBMITTED`. | Bundle succeeds (`200 OK`, project status `ACTIVE`); `MAIN` approval fails with **`422`** until all `SUB` permits are `APPROVED`. |
| **`TC-POL-03`** | `BUNDLED` + `BOTTOM_UP` + `SYNCHRONIZED` | Approve all `SUB` permits → approve `MAIN` permit. | **`200 OK`** — `MAIN` reaches `APPROVED`, and parent `Project.status` transitions to `COMPLETED`. |
| **`TC-POL-04`** | `MAIN_FIRST` + `TOP_DOWN` + `DECOUPLED` | Submit `SUB` permit while `MAIN` is still `DRAFT`. | **`422 Unprocessable Entity`** — `MAIN` must be submitted before `SUB`. |
| **`TC-POL-05`** | `MAIN_FIRST` + `TOP_DOWN` + `DECOUPLED` | Submit `MAIN` → submit `SUB` → approve `SUB` while `MAIN` is `SUBMITTED`. | Submissions succeed (`200 OK`); `SUB` approval fails with **`422`** until `MAIN` is `APPROVED`. |
| **`TC-POL-06`** | `INDEPENDENT` + `INDEPENDENT` + `DECOUPLED` | Submit and approve `SUB` and `MAIN` in arbitrary order. | **`200 OK`** — All transitions succeed independently; `Project.status` becomes `COMPLETED` after final permit approval. |

---

## 3. Resubmission & Sibling Hold Test Cases

| Test ID | Policy Mode | Scenario | Expected Result |
| :--- | :--- | :--- | :--- |
| **`TC-RES-01`** | `SYNCHRONIZED` | `MAIN` and `SUB` are `SUBMITTED`. Reviewer calls `request-corrections` on `SUB`. | **`200 OK`** — `SUB` moves to `CORRECTIONS_REQUIRED`; `MAIN` automatically transitions to `ON_HOLD_FOR_SIBLINGS` with system audit note. |
| **`TC-RES-02`** | `SYNCHRONIZED` | Following `TC-RES-01`, applicant calls `resubmit` on `SUB`. | **`200 OK`** — `SUB.resubmissionCycle` increments (`0 → 1`), `SUB` returns to `SUBMITTED`, and `MAIN` is released from `ON_HOLD_FOR_SIBLINGS` back to `SUBMITTED`. |
| **`TC-RES-03`** | `DECOUPLED` | `MAIN` and `SUB` are `SUBMITTED`. Reviewer calls `request-corrections` on `SUB`. | **`200 OK`** — `SUB` moves to `CORRECTIONS_REQUIRED`; `MAIN` remains `SUBMITTED` and can still be approved independently. |

---

## 4. Mandatory Comments & Audit Trail Test Cases

| Test ID | Endpoint / Scenario | Input Payload | Expected Result |
| :--- | :--- | :--- | :--- |
| **`TC-AUD-01`** | Any transition endpoint (`/submit`, `/submit-bundle`, `/approve`, `/request-corrections`, `/resubmit`) | `{"actorId": "...", "comments": ""}` or `null` or `"   "` | **`400 Bad Request`** — Transition rejected; permit status and `permit_status_history` remain unchanged. |
| **`TC-AUD-02`** | Full lifecycle (`create` → `submit-bundle` → `request-corrections` → `resubmit` → `approve`) | Valid non-blank comments on each step | `GET /permits/{id}/history` returns 5 chronological entries (`createdAt ASC`) with exact `fromStatus`, `toStatus`, `policyApplied`, `actorId`, and trimmed `comments`. |

---

## 5. Config Pinning & Concurrency Test Cases

| Test ID | Scenario | Verification Assertion |
| :--- | :--- | :--- |
| **`TC-PIN-01`** | Create `Project A` under Jurisdiction `v1` (`BUNDLED`), then activate `v2` (`INDEPENDENT`) for the same jurisdiction and create `Project B`. | `Project A` still enforces `BUNDLED` (`422` on single submit), while `Project B` allows single permit submission (`200 OK`). |
| **`TC-CON-01`** | Two threads concurrently invoke `approvePermit(MAIN)` and `requestCorrections(SUB)` on the same `BOTTOM_UP` project using `CountDownLatch` / `ExecutorService`. | Pessimistic lock (`findByIdWithLock`) serializes both transactions: either `SUB` moves to `CORRECTIONS_REQUIRED` first and `MAIN` approval fails with `422`, or `MAIN` completes first. Database never reaches an illegal `MAIN=APPROVED` + `SUB=CORRECTIONS_REQUIRED` state. |
| **`TC-DB-01`** | Full Flyway migration (`V1` → `V3`) and lifecycle transitions executed against H2 (`DB_CLOSE_DELAY=-1`) and PostgreSQL. | All `CHECK` constraints (`DRAFT`, `SUBMITTED`, `APPROVED`, `CORRECTIONS_REQUIRED`, `ON_HOLD_FOR_SIBLINGS`) pass without SQL state errors. |
# 04 — OpenAPI 3 REST Contract & Error Specification

**Version:** 1.0 (Baseline)

**Base Path:** `/api/v1`

**Controllers:** `JurisdictionController` (4 endpoints) · `ProjectWorkflowController` (8 endpoints)

**OpenAPI Endpoints:** `/swagger-ui.html` (UI) · `/v3/api-docs` (JSON Schema)

---

## 1. API Design Conventions

* **Content Type:** All request and response bodies use `application/json` (UTF-8).
* **Identifiers:** All primary keys and actor references use RFC 4122 UUID strings (`36` characters).
* **Timestamps:** All timestamps are serialized in ISO-8601 UTC format (`Instant`, e.g., `2026-11-18T12:00:00Z`).
* **Audit Comment Guard:** All 5 state-transition endpoints require a non-blank `comments` field (`@NotBlank`). Leading and trailing whitespace is trimmed prior to persistence in `permit_status_history`.
* **Error Payloads:** All `4xx`/`5xx` error responses conform to **RFC 7807 `ProblemDetail`**.

---

## 2. Endpoint Summary Matrix (All 12 Endpoints)

### 2.1 Jurisdiction & Policy Administration (`JurisdictionController`)

| # | Method | Path | Success Code | Description |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `POST` | `/api/v1/jurisdictions` | `201 Created` | Register a new active municipal jurisdiction |
| **2** | `GET` | `/api/v1/jurisdictions` | `200 OK` | List all active jurisdictions |
| **3** | `POST` | `/api/v1/jurisdictions/{jurisdictionId}/config` | `201 Created` | Publish a new immutable workflow policy version |
| **4** | `GET` | `/api/v1/jurisdictions/{jurisdictionId}/config` | `200 OK` | Retrieve the currently active workflow configuration |

### 2.2 Project & Permit Workflow Execution (`ProjectWorkflowController`)

| # | Method | Path | Success Code | Description |
| :--- | :--- | :--- | :--- | :--- |
| **5** | `POST` | `/api/v1/projects` | `201 Created` | Create a project and instantiate `DRAFT` permits |
| **6** | `GET` | `/api/v1/projects/{projectId}` | `200 OK` | Retrieve project details, pinned policy, and permit tree |
| **7** | `POST` | `/api/v1/projects/{projectId}/submit-bundle` | `200 OK` | Atomically submit all `DRAFT` permits in a project |
| **8** | `POST` | `/api/v1/permits/{permitId}/submit` | `200 OK` | Submit a single `DRAFT` permit (`MAIN_FIRST` / policy-checked) |
| **9** | `POST` | `/api/v1/permits/{permitId}/approve` | `200 OK` | Approve a `SUBMITTED` permit under `ApprovalPolicy` |
| **10** | `POST` | `/api/v1/permits/{permitId}/request-corrections` | `200 OK` | Request corrections (and auto-hold siblings if `SYNCHRONIZED`) |
| **11** | `POST` | `/api/v1/permits/{permitId}/resubmit` | `200 OK` | Resubmit a permit after corrections (`resubmissionCycle + 1`) |
| **12** | `GET` | `/api/v1/permits/{permitId}/history` | `200 OK` | Fetch chronological immutable audit trail (`createdAt ASC`) |

---

## 3. Detailed Endpoint Contracts — Jurisdiction Administration

### 3.1 `POST /api/v1/jurisdictions`
Creates a new active municipality.

* **Request Body (`CreateJurisdictionRequest`):**
  ```json
  {
    "name": "City of Miami",
    "stateCode": "FL"
  }
  ```
    * `name` (`String`, required, non-blank)
    * `stateCode` (`String`, required, exactly 2 characters; normalized to uppercase)
* **Response (`201 Created` — `JurisdictionSummary`):**
  ```json
  {
    "id": "55555555-5555-5555-5555-555555555555",
    "name": "City of Miami",
    "stateCode": "FL"
  }
  ```

### 3.2 `GET /api/v1/jurisdictions`
Returns all active municipalities (`isActive == true`).

* **Response (`200 OK` — `List<JurisdictionSummary>`):**
  ```json
  [
    {
      "id": "11111111-1111-1111-1111-111111111111",
      "name": "City of Tampa",
      "stateCode": "FL"
    },
    {
      "id": "33333333-3333-3333-3333-333333333333",
      "name": "City of Austin",
      "stateCode": "TX"
    }
  ]
  ```

### 3.3 `POST /api/v1/jurisdictions/{jurisdictionId}/config`
Publishes a new workflow configuration version for `jurisdictionId`, marks `isCurrent = true`, retires any prior active configuration (`isCurrent = false`), and increments `versionNumber`.

* **Path Parameter:** `jurisdictionId` (`UUID`, required)
* **Validation Invariants:**
    * `submissionMode` must be `BUNDLED` or `MAIN_FIRST`.
    * `approvalMode` must be `BOTTOM_UP`, `TOP_DOWN`, or `INDEPENDENT`.
    * `resubmissionMode` must be `SYNCHRONIZED` or `DECOUPLED`.
    * `mappings` must be non-empty and contain **exactly one** item with `"hierarchyRole": "MAIN"`.
    * Every `permitTypeCode` must match an active record in `permit_types` with no duplicate codes.
* **Request Body (`PublishWorkflowConfigRequest`):**
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
      },
      {
        "permitTypeId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2",
        "permitTypeCode": "ELECTRICAL",
        "permitTypeName": "Electrical Permit",
        "hierarchyRole": "SUB",
        "isMandatory": true
      },
      {
        "permitTypeId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5",
        "permitTypeCode": "SOLAR",
        "permitTypeName": "Solar PV Installation Permit",
        "hierarchyRole": "SUB",
        "isMandatory": false
      }
    ]
  }
  ```

### 3.4 `GET /api/v1/jurisdictions/{jurisdictionId}/config`
Fetches the currently active (`isCurrent = true`) workflow configuration and permit mappings for `jurisdictionId`.

* **Response (`200 OK`):** `ActiveConfigResponse` schema (same as 3.3).
* **Error (`404 Not Found`):** Returned if the jurisdiction has no active configuration published.

---

## 4. Detailed Endpoint Contracts — Project & Permit Lifecycle

### 4.1 `POST /api/v1/projects`
Creates a new `Project` pinned to the jurisdiction's current active configuration, instantiates the `MAIN` permit, all `isMandatory = true` `SUB` permits, and any requested `selectedOptionalPermitTypeIds` in `DRAFT` status, and writes `PROJECT_INIT` audit entries.

* **Request Body (`CreateProjectRequest`):**
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
      },
      {
        "id": "88888888-8888-8888-8888-000000000002",
        "parentPermitId": "88888888-8888-8888-8888-000000000001",
        "permitTypeCode": "PLUMBING",
        "permitTypeName": "Plumbing Permit",
        "status": "DRAFT",
        "resubmissionCycle": 0,
        "updatedAt": "2026-11-18T12:00:00Z"
      }
    ],
    "createdAt": "2026-11-18T12:00:00Z"
  }
  ```

### 4.2 `GET /api/v1/projects/{projectId}`
Returns the full `ProjectDetailsResponse` including the pinned `appliedPolicy` snapshot and live status of all child permits.

### 4.3 Shared Transition Payload (`PermitActionRequest`)
Used by all 5 mutating lifecycle endpoints (`submit-bundle`, `submit`, `approve`, `request-corrections`, `resubmit`):

```json
{
  "actorId": "99999999-9999-9999-9999-000000000002",
  "comments": "Sheet E-2 verified against NEC 2023 load requirements."
}
```
* `actorId` (`UUID`, required)
* `comments` (`String`, required, `@NotBlank`)

### 4.4 `POST /api/v1/projects/{projectId}/submit-bundle`
Transitions all `DRAFT` permits in `projectId` to `SUBMITTED` atomically and sets `Project.status = ACTIVE`.
* **Response (`200 OK`):** `ProjectDetailsResponse`
* **Error (`422`):** Project has zero `DRAFT` permits eligible for bundle submission.

### 4.5 `POST /api/v1/permits/{permitId}/submit`
Submits a single `DRAFT` permit after evaluating `SubmissionPolicy`.
* **Response (`200 OK`):** `PermitResponse` (`status: "SUBMITTED"`)
* **Error (`422`):** Permit is not `DRAFT`, jurisdiction enforces `BUNDLED`, or `MAIN_FIRST` detects `MAIN` is still `DRAFT`.

### 4.6 `POST /api/v1/permits/{permitId}/approve`
Approves a `SUBMITTED` (or `IN_REVIEW`) permit after evaluating `ApprovalPolicy`. Automatically transitions `Project.status` to `COMPLETED` when all permits in the project are `APPROVED`.
* **Response (`200 OK`):** `PermitResponse` (`status: "APPROVED"`)
* **Error (`422`):** Permit is not `SUBMITTED`/`IN_REVIEW` (e.g., already `APPROVED`, `DRAFT`, `CORRECTIONS_REQUIRED`, or `ON_HOLD_FOR_SIBLINGS`) or `ApprovalPolicy` denies approval.

### 4.7 `POST /api/v1/permits/{permitId}/request-corrections`
Moves a `SUBMITTED` (or `IN_REVIEW`) permit to `CORRECTIONS_REQUIRED`. Under `SYNCHRONIZED` mode, active sibling permits automatically move to `ON_HOLD_FOR_SIBLINGS`.
* **Response (`200 OK`):** `List<PermitResponse>` containing the target permit plus any sibling permits placed on hold.
* **Error (`422`):** Target permit is not in `SUBMITTED` or `IN_REVIEW`.

### 4.8 `POST /api/v1/permits/{permitId}/resubmit`
Transitions a `CORRECTIONS_REQUIRED` permit back to `SUBMITTED` and increments `resubmissionCycle` by `1`. Under `SYNCHRONIZED` mode, releases siblings from `ON_HOLD_FOR_SIBLINGS` back to `SUBMITTED` once no sibling remains in `CORRECTIONS_REQUIRED`.
* **Response (`200 OK`):** `PermitResponse`
* **Error (`422`):** Target permit is not in `CORRECTIONS_REQUIRED`.

### 4.9 `GET /api/v1/permits/{permitId}/history`
Returns the immutable audit log for `permitId` sorted chronologically (`createdAt ASC`).

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

## 5. RFC 7807 `ProblemDetail` Error Contract

All exceptions handled by `GlobalExceptionHandler` return standard `application/problem+json` payloads:

```json
{
  "type": "about:blank",
  "title": "Workflow Rule Violation",
  "status": 422,
  "detail": "BUNDLED submission policy requires submitting all project permits together via /submit-bundle.",
  "instance": "/api/v1/permits/88888888-8888-8888-8888-000000000001/submit"
}
```

| HTTP Status | Title | Triggering Exception |
| :--- | :--- | :--- |
| **`400 Bad Request`** | `Bad Request` / `Validation Failed` | `IllegalArgumentException`, `MethodArgumentNotValidException` (blank comments, invalid UUID/state code, invalid config mapping) |
| **`404 Not Found`** | `Resource Not Found` | `ResourceNotFoundException` (jurisdiction has no active workflow config) |
| **`409 Conflict`** | `Concurrency Conflict` | `OptimisticLockingFailureException` / `PessimisticLockingFailureException` |
| **`422 Unprocessable Entity`** | `Workflow Rule Violation` | `WorkflowRuleViolationException` (policy denial or invalid permit state transition) |
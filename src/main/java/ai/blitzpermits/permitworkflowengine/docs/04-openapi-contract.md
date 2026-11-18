# 04 — REST API & Error Contract Specification

**Service:** `permit-workflow-engine`  
**Base Path:** `/api/v1`  
**Controller:** `ProjectWorkflowController`  
**OpenAPI / Swagger UI:** `/swagger-ui.html`  
**Status:** Baseline v1.0

---

## 1. Endpoint Summary

| Method | Endpoint | Purpose | Success Code |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/projects` | Create project & instantiate `MAIN` + `SUB` permits in `DRAFT` | `201 Created` |
| `GET` | `/api/v1/projects/{projectId}` | Fetch project details, pinned policy modes, and permit tree | `200 OK` |
| `POST` | `/api/v1/projects/{projectId}/submit-bundle` | Submit all `DRAFT` permits in project simultaneously | `200 OK` |
| `POST` | `/api/v1/permits/{permitId}/submit` | Submit an individual `DRAFT` permit | `200 OK` |
| `POST` | `/api/v1/permits/{permitId}/approve` | Approve a `SUBMITTED` permit | `200 OK` |
| `POST` | `/api/v1/permits/{permitId}/request-corrections` | Issue corrections on a permit (and auto-hold siblings if `SYNCHRONIZED`) | `200 OK` |
| `POST` | `/api/v1/permits/{permitId}/resubmit` | Resubmit a `CORRECTIONS_REQUIRED` permit (`resubmissionCycle + 1`) | `200 OK` |
| `GET` | `/api/v1/permits/{permitId}/history` | Retrieve chronological audit trail (`createdAt ASC`) | `200 OK` |

---

## 2. Request & Response Contracts

### 2.1 Create Project (`POST /api/v1/projects`)
**Request (`CreateProjectRequest`):**
```json
{
  "applicantId": "11111111-1111-1111-1111-111111111111",
  "jurisdictionId": "22222222-2222-2222-2222-222222222222",
  "selectedOptionalPermitTypeIds": [
    "33333333-3333-3333-3333-333333333333"
  ]
}
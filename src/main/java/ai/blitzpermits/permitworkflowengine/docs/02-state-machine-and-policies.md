# 02 — State Machine & Policy Strategy Specification

**Version:** 1.0 (Baseline)  
**Target Package:** `ai.blitzpermits.permitworkflowengine.engine`  
**Service Bounded Context:** Permit Lifecycle Transitions & Pluggable Municipal Policy Evaluation

---

## 1. Overview

The `permit-workflow-engine` separates **structural state transitions** (managed by `PermitLifecycleService`) from **jurisdiction-specific regulatory rules** (evaluated by strategy implementations registered in `PolicyRegistry`). Every state mutation validates structural preconditions first, evaluates the project's pinned policy strategy (`Allowed` vs. `Denied`), mutates entities atomically under a project-level lock, and writes an immutable `PermitStatusHistory` audit entry.

---

## 2. Project Lifecycle State Machine (`projects.status`)

A `Project` acts as the aggregate root and tracks coarse-grained lifecycle progress derived automatically from its child permits.

| State | Entry Condition | Exit Condition | Allowed Mutations |
| :--- | :--- | :--- | :--- |
| **`DRAFT`** | Initial state upon `POST /api/v1/projects`. All instantiated permits are in `DRAFT`. | Transitions automatically to `ACTIVE` when at least one permit (or the full bundle) is submitted. | `submit-bundle`, `submit` (on individual permits) |
| **`ACTIVE`** | At least one permit in the project has left `DRAFT` (`SUBMITTED`, `IN_REVIEW`, `CORRECTIONS_REQUIRED`, `ON_HOLD_FOR_SIBLINGS`, or `APPROVED`). | Transitions automatically to `COMPLETED` when **100%** of project permits reach `APPROVED` (or `CANCELLED` if terminated). | `submit-bundle` (remaining `DRAFT` permits), `submit`, `approve`, `request-corrections`, `resubmit` |
| **`COMPLETED`** | Terminal state reached when every `MAIN` and `SUB` permit in the project is `APPROVED`. | None (terminal). | Read-only (`GET /projects/{id}`, `GET /permits/{id}/history`) |
| **`CANCELLED`** | Terminal administrative cancellation prior to completion. | None (terminal). | Read-only |

---

## 3. Permit Lifecycle State Machine (`permits.status`)

Each individual `Permit` (`MAIN` or `SUB`) follows a deterministic six-state lifecycle.

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

### 3.1 Permit State Definitions & Invariants

| State | Description & Invariants |
| :--- | :--- |
| **`DRAFT`** | Initial state when instantiated at project creation (`resubmissionCycle = 0`). Cannot be approved or sent for corrections until submitted. |
| **`SUBMITTED`** | Filed by the applicant and eligible for municipal plan review, approval, or correction requests. |
| **`IN_REVIEW`** | Reserved state indicating active assignment to a municipal plan reviewer. Treated equivalently to `SUBMITTED` for sibling hold eligibility. |
| **`CORRECTIONS_REQUIRED`** | Reviewer rejected the current plan set with mandatory comments. Awaits applicant revision and `/resubmit`. Cannot be approved directly from this state. |
| **`ON_HOLD_FOR_SIBLINGS`** | System-applied hold placed on active sibling permits (`SUBMITTED` / `IN_REVIEW`) when another permit in the project enters `CORRECTIONS_REQUIRED` under `SYNCHRONIZED` mode. Cannot be approved while on hold. |
| **`APPROVED`** | Terminal state for an individual permit. Once `APPROVED`, a permit cannot be re-approved (`422`), resubmitted (`422`), or placed on sibling hold. |

### 3.2 Complete Transition Matrix

| Action / Endpoint | Valid `fromStatus` | Resulting `toStatus` | Policy Evaluated | Side Effects & Rejections (`422`) |
| :--- | :--- | :--- | :--- | :--- |
| **`submit-bundle`**<br>`POST /projects/{id}/submit-bundle` | `DRAFT` (all `DRAFT` permits in project) | `SUBMITTED` | `BUNDLED` (recorded in audit) | Fails with `422` if the project contains **zero** `DRAFT` permits. Transitions `Project.status` from `DRAFT` -> `ACTIVE`. |
| **`submit`**<br>`POST /permits/{id}/submit` | `DRAFT` | `SUBMITTED` | `SubmissionPolicy` (`BUNDLED` \| `MAIN_FIRST`) | Fails with `422` if permit is not `DRAFT` or if `SubmissionPolicy` returns `Denied`. Transitions `Project.status` -> `ACTIVE`. |
| **`approve`**<br>`POST /permits/{id}/approve` | `SUBMITTED` (or `IN_REVIEW`) | `APPROVED` | `ApprovalPolicy` (`BOTTOM_UP` \| `TOP_DOWN` \| `INDEPENDENT`) | Fails with `422` if already `APPROVED`, in `DRAFT`, `CORRECTIONS_REQUIRED`, `ON_HOLD_FOR_SIBLINGS`, or if `ApprovalPolicy` returns `Denied`. If all siblings are now `APPROVED`, transitions `Project.status` -> `COMPLETED`. |
| **`request-corrections`**<br>`POST /permits/{id}/request-corrections` | `SUBMITTED` (or `IN_REVIEW`) | `CORRECTIONS_REQUIRED` | `ResubmissionPolicy` (`SYNCHRONIZED` \| `DECOUPLED`) | Fails with `422` if permit is `DRAFT`, `APPROVED`, or already `CORRECTIONS_REQUIRED`. Under `SYNCHRONIZED`, moves active siblings to `ON_HOLD_FOR_SIBLINGS`. |
| **`resubmit`**<br>`POST /permits/{id}/resubmit` | `CORRECTIONS_REQUIRED` | `SUBMITTED` | `ResubmissionPolicy` (`SYNCHRONIZED` \| `DECOUPLED`) | Fails with `422` if permit is not `CORRECTIONS_REQUIRED`. Increments `resubmissionCycle` (`+1`). Under `SYNCHRONIZED`, releases held siblings back to `SUBMITTED` once no sibling remains in `CORRECTIONS_REQUIRED`. |

---

## 4. Policy Strategy Architecture (`engine`)

Policies are implemented via the **Strategy Pattern** combined with Java 21 **Sealed Interfaces** (`PolicyDecision`) and indexed at startup in `PolicyRegistry`.

```java
public sealed interface PolicyDecision permits PolicyDecision.Allowed, PolicyDecision.Denied {
    record Allowed() implements PolicyDecision {}
    record Denied(String reason) implements PolicyDecision {}
}
```

When any strategy returns `PolicyDecision.Denied(reason)`, `PermitLifecycleService` throws `WorkflowRuleViolationException(reason)`, which `GlobalExceptionHandler` translates into an **HTTP `422 Unprocessable Entity`** RFC 7807 `ProblemDetail` response.

---

## 5. Detailed Policy Rules by Dimension

### 5.1 Dimension 1: Submission Policy (`SubmissionPolicy`)
Governs individual permit submission (`POST /api/v1/permits/{permitId}/submit`).

* **`BUNDLED` (`BundledSubmissionPolicy`):**
    * **Rule:** Individual permit submission is **always denied** (`422 Unprocessable Entity`), regardless of whether the target is `MAIN` or `SUB`.
    * **Required Path:** Applicants must submit all `DRAFT` permits together via `POST /api/v1/projects/{projectId}/submit-bundle`.
    * **Example Municipality:** City of Tampa, FL.
* **`MAIN_FIRST` (`MainFirstSubmissionPolicy`):**
    * **Rule:**
        * If the target permit is the `MAIN` permit (`parentPermit == null`), submission is **Allowed**.
        * If the target permit is a `SUB` permit (`parentPermit != null`), submission is **Allowed** only if the project's `MAIN` permit is **no longer in `DRAFT`** (`SUBMITTED`, `IN_REVIEW`, `CORRECTIONS_REQUIRED`, `ON_HOLD_FOR_SIBLINGS`, or `APPROVED`). If `MAIN` is still `DRAFT`, returns `Denied` (`422`).
    * **Example Municipality:** City of Austin, TX.

### 5.2 Dimension 2: Approval Policy (`ApprovalPolicy`)
Governs permit approval (`POST /api/v1/permits/{permitId}/approve`).

* **`BOTTOM_UP` (`BottomUpApprovalPolicy`):**
    * **Rule:**
        * If the target permit is a `SUB` permit (`parentPermit != null`), approval is **Allowed** independently of sibling states.
        * If the target permit is the `MAIN` permit (`parentPermit == null`), approval is **Allowed** if and only if **every `SUB` permit** in the project currently has `status == APPROVED`. If any `SUB` permit is not `APPROVED`, returns `Denied` (`422`).
    * **Example Municipality:** City of Tampa, FL.
* **`TOP_DOWN` (`TopDownApprovalPolicy`):**
    * **Rule:**
        * If the target permit is the `MAIN` permit (`parentPermit == null`), approval is **Allowed**.
        * If the target permit is a `SUB` permit (`parentPermit != null`), approval is **Allowed** if and only if the project's `MAIN` permit already has `status == APPROVED`. Otherwise returns `Denied` (`422`).
    * **Example Municipality:** City of Austin, TX.
* **`INDEPENDENT` (`IndependentApprovalPolicy`):**
    * **Rule:** Always returns `Allowed` for any eligible `SUBMITTED` permit (`MAIN` or `SUB`) regardless of sibling or parent approval status.

### 5.3 Dimension 3: Resubmission & Sibling Coupling Policy (`ResubmissionPolicy`)
Governs `request-corrections` and `resubmit` transitions.

* **`SYNCHRONIZED` (`SynchronizedResubmissionPolicy`):**
    * **On `request-corrections` (Target Permit `P`):**
        1. `P` transitions from `SUBMITTED` -> `CORRECTIONS_REQUIRED` (audited with `policyApplied = "SYNCHRONIZED"`).
        2. Every sibling permit `S` (`S.id != P.id`) currently in `SUBMITTED` or `IN_REVIEW` automatically transitions to `ON_HOLD_FOR_SIBLINGS`, writing a system audit record (`policyApplied = "SYNCHRONIZED"`, noting the triggering permit code).
        3. Siblings already in `APPROVED`, `DRAFT`, `CORRECTIONS_REQUIRED`, or `ON_HOLD_FOR_SIBLINGS` remain untouched.
    * **On `resubmit` (Target Permit `P`):**
        1. `P` transitions from `CORRECTIONS_REQUIRED` -> `SUBMITTED` and increments `P.resubmissionCycle` by `1`.
        2. If **no** sibling permit remains in `CORRECTIONS_REQUIRED`, all siblings currently in `ON_HOLD_FOR_SIBLINGS` automatically transition back to `SUBMITTED` with an audit entry releasing the hold. If another sibling still requires corrections, held siblings remain in `ON_HOLD_FOR_SIBLINGS` until all corrections are resolved.
    * **Example Municipality:** City of Tampa, FL.
* **`DECOUPLED` (`DecoupledResubmissionPolicy`):**
    * **On `request-corrections` (Target Permit `P`):**
        * Only `P` transitions to `CORRECTIONS_REQUIRED`. Sibling permits are **never** placed on `ON_HOLD_FOR_SIBLINGS` and may continue through review and approval independently.
    * **On `resubmit` (Target Permit `P`):**
        * Only `P` transitions to `SUBMITTED` and increments `P.resubmissionCycle` by `1`.
    * **Example Municipality:** City of Austin, TX.

---

## 6. Policy Interaction Edge Cases & Invariants

1. **Held Sibling Approval Guard:** Even under `INDEPENDENT` or `BOTTOM_UP` approval modes, a permit in `ON_HOLD_FOR_SIBLINGS` is structurally ineligible for `/approve` (`422 Unprocessable Entity`) until the sibling hold is cleared via `/resubmit`.
2. **Multiple Simultaneous Corrections under `SYNCHRONIZED`:** If Permit A enters `CORRECTIONS_REQUIRED` (putting Permit B on hold) and Permit C was also placed in `CORRECTIONS_REQUIRED` prior to hold, resubmitting Permit A keeps Permit B in `ON_HOLD_FOR_SIBLINGS` until Permit C is also resubmitted.
3. **Audit Policy Attribution:** Every `PermitStatusHistory` row records the exact policy identifier applied (`PROJECT_INIT`, `BUNDLED`, `MAIN_FIRST`, `BOTTOM_UP`, `TOP_DOWN`, `INDEPENDENT`, `SYNCHRONIZED`, or `DECOUPLED`) so compliance auditors can trace why a transition occurred.
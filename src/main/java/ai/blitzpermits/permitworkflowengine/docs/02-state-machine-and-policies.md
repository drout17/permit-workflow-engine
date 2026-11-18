# 02 — State Machine & Policy Engine Specification

**Service:** `permit-workflow-engine`  
**Package:** `ai.blitzpermits.permitworkflowengine.engine`  
**Status:** Baseline v1.0

---

## 1. Permit State Machine

Every `Permit` within a `Project` progresses through a finite state machine governed by explicit lifecycle actions and jurisdiction-pinned policies:

```text
[null] ──(createProject)──► [DRAFT]
                              │
             (submit / submit-bundle)
                              │
                              ▼
                         [SUBMITTED] ◄─────────(resubmit)─────────┐
                           │     │                                │
                (approve)  │     └──(request-corrections)──► [CORRECTIONS_REQUIRED]
                   │       │                                      ▲
                   ▼       │ (SYNCHRONIZED sibling hold)          │ (sibling resubmitted)
              [APPROVED]   └─────────────────────────────► [ON_HOLD_FOR_SIBLINGS]
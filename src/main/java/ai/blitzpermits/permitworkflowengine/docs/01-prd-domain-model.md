# 01 — Product Requirements & Domain Model

**Service:** `permit-workflow-engine`  
**Base Package:** `ai.blitzpermits.permitworkflowengine`  
**Status:** Baseline v1.0

---

## 1. Product Purpose
`permit-workflow-engine` is a multi-tenant, policy-driven workflow service that orchestrates municipal construction permitting lifecycles. Rather than hardcoding city-specific review rules into application code, the engine models workflows as versioned, data-driven configurations per jurisdiction, governing how primary and subordinate permits are submitted, reviewed, corrected, resubmitted, and approved.

---

## 2. Core Domain Entities & Relationships

```text
Jurisdiction (1) ───────< (N) JurisdictionWorkflowConfig [Versioned, 1 active via isCurrent=true]
                                      │
                                      ├──< (N) JurisdictionPermitMapping [MAIN / SUB, isMandatory]
                                      │
Project (N) ───────► (1) JurisdictionWorkflowConfig [Pinned at project creation via appliedConfig]
   │
   └──< (N) Permit [1 MAIN permit + 0..N SUB permits]
               │
               └──< (N) PermitStatusHistory [Immutable append-only audit trail]
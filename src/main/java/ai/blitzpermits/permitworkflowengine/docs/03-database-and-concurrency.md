# 03 — Database Schema, Migrations & Concurrency Specification

**Service:** `permit-workflow-engine`  
**Persistence:** Spring Data JPA / Hibernate 7 + Flyway  
**Databases:** H2 (Local/Test) & PostgreSQL (Production)  
**Status:** Baseline v1.0

---

## 1. Entity-Relationship Schema

### Core Tables & Columns

1. **`permit_types`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `code` (`VARCHAR(50)`, UNIQUE, NOT NULL — e.g., `BUILDING_MAIN`, `ELECTRICAL_SUB`)
   * `name` (`VARCHAR(150)`, NOT NULL)
   * `description` (`VARCHAR(500)`, NULLABLE)
   * `is_active` (`BOOLEAN`, NOT NULL, DEFAULT `TRUE`)
   * `created_at` (`TIMESTAMP WITH TIME ZONE`, NOT NULL, DEFAULT `CURRENT_TIMESTAMP`)

2. **`jurisdictions`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `name` (`VARCHAR(150)`, NOT NULL)
   * `state_code` (`VARCHAR(10)`, NOT NULL)
   * `is_active` (`BOOLEAN`, NOT NULL, DEFAULT `TRUE`)
   * `created_at` (`TIMESTAMP WITH TIME ZONE`, NOT NULL, DEFAULT `CURRENT_TIMESTAMP`)

3. **`jurisdiction_workflow_configs`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `jurisdiction_id` (`UUID`, FK → `jurisdictions.id` `ON DELETE CASCADE`, NOT NULL)
   * `version_number` (`INT`, NOT NULL)
   * `is_current` (`BOOLEAN`, NOT NULL, DEFAULT `FALSE`)
   * `submission_mode` (`VARCHAR(30)`, NOT NULL — `CHECK (submission_mode IN ('BUNDLED', 'MAIN_FIRST'))`)
   * `approval_mode` (`VARCHAR(30)`, NOT NULL — `CHECK (approval_mode IN ('BOTTOM_UP', 'TOP_DOWN', 'INDEPENDENT'))`)
   * `resubmission_mode` (`VARCHAR(30)`, NOT NULL — `CHECK (resubmission_mode IN ('SYNCHRONIZED', 'DECOUPLED'))`)
   * `custom_rules_json` (`CLOB`, NULLABLE)
   * `created_at` (`TIMESTAMP WITH TIME ZONE`, NOT NULL, DEFAULT `CURRENT_TIMESTAMP`)
   * **Constraints:** `UNIQUE (jurisdiction_id, version_number)`; at most one active configuration (`is_current = TRUE`) per `jurisdiction_id`.

4. **`jurisdiction_permit_mappings`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `config_id` (`UUID`, FK → `jurisdiction_workflow_configs.id` `ON DELETE CASCADE`, NOT NULL)
   * `permit_type_id` (`UUID`, FK → `permit_types.id` `ON DELETE RESTRICT`, NOT NULL)
   * `hierarchy_role` (`VARCHAR(20)`, NOT NULL — `CHECK (hierarchy_role IN ('MAIN', 'SUB'))`)
   * `is_mandatory` (`BOOLEAN`, NOT NULL, DEFAULT `FALSE`)
   * **Constraints:** `UNIQUE (config_id, permit_type_id)`

5. **`projects`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `applicant_id` (`UUID`, NOT NULL)
   * `jurisdiction_id` (`UUID`, FK → `jurisdictions.id`, NOT NULL)
   * `applied_config_id` (`UUID`, FK → `jurisdiction_workflow_configs.id`, NOT NULL — pins workflow version)
   * `status` (`VARCHAR(30)`, NOT NULL, DEFAULT `'DRAFT'` — `CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED'))`)
   * `created_at` (`TIMESTAMP WITH TIME ZONE`, NOT NULL, DEFAULT `CURRENT_TIMESTAMP`)

6. **`permits`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `project_id` (`UUID`, FK → `projects.id` `ON DELETE CASCADE`, NOT NULL)
   * `parent_permit_id` (`UUID`, FK → `permits.id` `ON DELETE CASCADE`, NULLABLE — `NULL` for `MAIN`, points to `MAIN` for `SUB`)
   * `permit_type_id` (`UUID`, FK → `permit_types.id` `ON DELETE RESTRICT`, NOT NULL)
   * `status` (`VARCHAR(30)`, NOT NULL, DEFAULT `'DRAFT'` — `CHECK (status IN ('DRAFT', 'SUBMITTED', 'IN_REVIEW', 'CORRECTIONS_REQUIRED', 'ON_HOLD_FOR_SIBLINGS', 'APPROVED'))`)
   * `resubmission_cycle` (`INT`, NOT NULL, DEFAULT `0`)
   * `version` (`BIGINT`, NOT NULL, DEFAULT `0` — optimistic locking version column)
   * `updated_at` (`TIMESTAMP WITH TIME ZONE`, NOT NULL, DEFAULT `CURRENT_TIMESTAMP`)
   * **Constraints:** `UNIQUE (project_id, permit_type_id)`

7. **`permit_status_history`**
   * `id` (`UUID`, DEFAULT `RANDOM_UUID()`, PK)
   * `permit_id` (`UUID`, FK → `permits.id` `ON DELETE CASCADE`, NOT NULL)
   * `from_status` (`VARCHAR(30)`, NULLABLE — `NULL` on initial `DRAFT` creation)
   * `to_status` (`VARCHAR(30)`, NOT NULL)
   * `policy_applied` (`VARCHAR(100)`, NULLABLE)
   * `actor_id` (`UUID`, NOT NULL)
   * `comments` (`TEXT`, NULLABLE at DDL level; enforced non-blank at application boundary)
   * `created_at` (`TIMESTAMP WITH TIME ZONE`, NOT NULL, DEFAULT `CURRENT_TIMESTAMP`)

---

## 2. Flyway Migration Chain

All schema evolution is strictly managed via sequential Flyway migrations under `src/main/resources/db/migration/`:

| Version | File | Purpose |
| :--- | :--- | :--- |
| **`V1`** | `V1__init_schema.sql` | Creates all 7 baseline tables (`permit_types`, `jurisdictions`, `jurisdiction_workflow_configs`, `jurisdiction_permit_mappings`, `projects`, `permits`, `permit_status_history`), foreign keys, unique/check constraints, and performance indexes. |
| **`V2`** | `V2__seed_jurisdictions.sql` | Seeds reference jurisdictions, permit types, and sample `JurisdictionWorkflowConfig` matrices for local testing. |

### H2 & PostgreSQL Compatibility Rules
* DDL scripts use standard SQL types (`UUID`, `VARCHAR`, `TEXT`, `CLOB`, `BOOLEAN`, `INT`, `BIGINT`, `TIMESTAMP WITH TIME ZONE`) compatible across H2 and PostgreSQL.
* Local H2 datasource must configure `DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` so connection pool (`HikariCP`) connections never target a closed in-memory database instance across requests or hot-reloads.

---

## 3. Concurrency Control & Locking Strategy

Because multi-permit policies (`BUNDLED`, `BOTTOM_UP`, `TOP_DOWN`, `SYNCHRONIZED`) evaluate invariants across **all permits in a project simultaneously**, concurrent updates to sibling permits must be serialized at the `Project` aggregate root, backed by a secondary optimistic `@Version` guard on `permits`.

### Pessimistic + Optimistic Locking Protocol
1. Every state-transition service method (`submitProjectBundle`, `submitSinglePermit`, `approvePermit`, `requestCorrections`, `resubmitPermit`) runs inside a single `@Transactional` boundary.
2. Immediately after validating mandatory `comments`, the service acquires a database row-level write lock (`PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE`) on the parent `Project` via `projectRepository.findByIdWithLock(projectId)`.
3. All sibling permit queries, policy evaluations, status mutations, and `permit_status_history` inserts execute while holding the `Project` row lock, releasing it only upon transaction commit.
4. `permits.version` provides secondary optimistic locking protection against stale entity updates.
5. Lock contention timeouts or version conflicts translate cleanly to HTTP `409 Conflict` via `GlobalExceptionHandler`.
# 03 — Database Schema, Flyway Migrations & Concurrency Specification

**Version:** 1.0 (Baseline)  
**Target Package:** `ai.blitzpermits.permitworkflowengine.entity` / `repository`  
**Migration Location:** `src/main/resources/db/migration/`  
**Persistence Stack:** Spring Data JPA · Hibernate 7 (`ddl-auto: validate`) · Flyway · H2 (`MODE=PostgreSQL`) / PostgreSQL 15+

---

## 1. Entity-Relationship Topology

The relational schema consists of **7 normalized tables** divided into two logical tiers:
* **Configuration & Catalog Tier (4 tables):** `permit_types`, `jurisdictions`, `jurisdiction_workflow_configs`, and `jurisdiction_permit_mappings`.
* **Transactional Runtime & Audit Tier (3 tables):** `projects`, `permits`, and `permit_status_history`.

```text
┌──────────────────┐         ┌───────────────────────────────────┐
│  jurisdictions   │ 1     N │   jurisdiction_workflow_configs   │
│──────────────────│────────►│───────────────────────────────────│
│ PK id (UUID)     │         │ PK id (UUID)                      │
│    name          │         │ FK jurisdiction_id                │
│    state_code    │         │    version_number (UNIQUE w/ jur) │
│    is_active     │         │    is_current                     │
└──────────────────┘         │    submission_mode                │
         │                   │    approval_mode                  │
         │ 1                 │    resubmission_mode              │
         │                   └─────────────────┬─────────────────┘
         │                                     │ 1
         │                                     ├───────────────────────────┐
         │ N                                   │ N                         │ 1
         ▼                                     ▼                           │
┌──────────────────┐         ┌───────────────────────────────────┐         │
│     projects     │         │   jurisdiction_permit_mappings    │         │
│──────────────────│         │───────────────────────────────────│         │
│ PK id (UUID)     │         │ PK id (UUID)                      │         │
│    applicant_id  │         │ FK config_id                      │         │
│ FK jurisdiction_id │       │ FK permit_type_id ────────┐       │         │
│ FK applied_config_id ◄─────┼───────────────────────────┼───────┘         │
│    status        │         │    hierarchy_role (MAIN | SUB)    │         │
└────────┬─────────┘         │    is_mandatory                   │         │
         │ 1                 └───────────────────────────────────┘         │
         │                                                                 │
         │ N                                                               │ 1
         ▼                                                                 ▼
┌────────────────────────────────────┐                           ┌──────────────────┐
│              permits               │ N                       1 │   permit_types   │
│────────────────────────────────────│──────────────────────────►│──────────────────│
│ PK id (UUID)                       │                           │ PK id (UUID)     │
│ FK project_id                      │                           │    code (UNIQUE) │
│ FK parent_permit_id (self-ref)     │                           │    name          │
│ FK permit_type_id                  │                           │    is_active     │
│    status                          │                           └──────────────────┘
│    resubmission_cycle              │
│    version (@Version)              │
└─────────────────┬──────────────────┘
                  │ 1
                  │
                  │ N
                  ▼
┌────────────────────────────────────┐
│       permit_status_history        │
│────────────────────────────────────│
│ PK id (UUID)                       │
│ FK permit_id                       │
│    from_status (nullable)          │
│    to_status                       │
│    policy_applied                  │
│    actor_id                        │
│    comments (NOT NULL)             │
│    created_at                      │
└────────────────────────────────────┘
```

---

## 2. Detailed Table Definitions & Database Constraints

### 2.1 `permit_types` (Global Permit Discipline Catalog)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `code` | `VARCHAR(64)` | `NOT NULL UNIQUE` | Machine-readable discipline code (e.g., `NEW_CONSTRUCTION`, `ELECTRICAL`) |
| `name` | `VARCHAR(120)` | `NOT NULL` | Human-readable display label |
| `description` | `TEXT` | `NULL` | Scope summary of the permit type |
| `is_active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Soft-disable flag for retired permit disciplines |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Creation timestamp |

### 2.2 `jurisdictions` (Multi-Tenant Municipalities)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `name` | `VARCHAR(150)` | `NOT NULL` | Municipality name (e.g., `City of Tampa`) |
| `state_code` | `CHAR(2)` | `NOT NULL` | Two-letter US state postal code (`FL`, `TX`) |
| `is_active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Soft-disable flag |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Registration timestamp |

### 2.3 `jurisdiction_workflow_configs` (Immutable Policy Versions)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `jurisdiction_id` | `UUID` | `NOT NULL FK -> jurisdictions(id) ON DELETE CASCADE` | Owning municipality |
| `version_number` | `INT` | `NOT NULL` | Sequential config version (`1, 2, 3...`) |
| `is_current` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` | Active config indicator (`TRUE` for at most one config per jurisdiction) |
| `submission_mode` | `VARCHAR(32)` | `NOT NULL CHECK IN ('BUNDLED', 'MAIN_FIRST')` | Submission policy strategy key |
| `approval_mode` | `VARCHAR(32)` | `NOT NULL CHECK IN ('BOTTOM_UP', 'TOP_DOWN', 'INDEPENDENT')` | Approval policy strategy key |
| `resubmission_mode` | `VARCHAR(32)` | `NOT NULL CHECK IN ('SYNCHRONIZED', 'DECOUPLED')` | Resubmission coupling strategy key |
| `custom_rules_json` | `TEXT` | `NULL` | Optional JSON extension payload (SLA days, fee metadata) |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Publication timestamp |

* **Table Constraints:** `UNIQUE (jurisdiction_id, version_number)` prevents duplicate version numbers within a jurisdiction.

### 2.4 `jurisdiction_permit_mappings` (Config Permit Template)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `config_id` | `UUID` | `NOT NULL FK -> jurisdiction_workflow_configs(id) ON DELETE CASCADE` | Parent workflow config version |
| `permit_type_id` | `UUID` | `NOT NULL FK -> permit_types(id) ON DELETE RESTRICT` | Referenced global permit type |
| `hierarchy_role` | `VARCHAR(16)` | `NOT NULL CHECK IN ('MAIN', 'SUB')` | Structural role in project permit tree |
| `is_mandatory` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` | Auto-instantiated on every project if `TRUE` |

* **Table Constraints:** `UNIQUE (config_id, permit_type_id)` prevents mapping the same permit type twice inside a single configuration version.

### 2.5 `projects` (Aggregate Root & Pessimistic Lock Target)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `applicant_id` | `UUID` | `NOT NULL` | External applicant / contractor identifier |
| `jurisdiction_id` | `UUID` | `NOT NULL FK -> jurisdictions(id) ON DELETE RESTRICT` | Filing municipality |
| `applied_config_id` | `UUID` | `NOT NULL FK -> jurisdiction_workflow_configs(id) ON DELETE RESTRICT` | Pinned workflow policy version snapshot |
| `status` | `VARCHAR(32)` | `NOT NULL CHECK IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED')` | Coarse-grained project lifecycle state |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Project creation timestamp |

### 2.6 `permits` (Hierarchical Permit Work Items)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `project_id` | `UUID` | `NOT NULL FK -> projects(id) ON DELETE CASCADE` | Owning project aggregate |
| `parent_permit_id` | `UUID` | `NULL FK -> permits(id) ON DELETE RESTRICT` | `NULL` for `MAIN` permit; references `MAIN` permit ID for `SUB` permits |
| `permit_type_id` | `UUID` | `NOT NULL FK -> permit_types(id) ON DELETE RESTRICT` | Global permit discipline |
| `status` | `VARCHAR(32)` | `NOT NULL CHECK IN ('DRAFT', 'SUBMITTED', 'IN_REVIEW', 'CORRECTIONS_REQUIRED', 'ON_HOLD_FOR_SIBLINGS', 'APPROVED')` | Current permit workflow state |
| `resubmission_cycle` | `INT` | `NOT NULL DEFAULT 0` | Counter incremented by `1` on every `/resubmit` |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` | JPA `@Version` column for optimistic concurrency control |
| `updated_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last state mutation timestamp |

* **Table Constraints:** `UNIQUE (project_id, permit_type_id)` guarantees at most one permit of a given discipline per project.

### 2.7 `permit_status_history` (Immutable Compliance Audit Ledger)
| Column | SQL Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Surrogate primary key |
| `permit_id` | `UUID` | `NOT NULL FK -> permits(id) ON DELETE CASCADE` | Audited permit |
| `from_status` | `VARCHAR(32)` | `NULL` | Prior permit status (`NULL` on initial `PROJECT_INIT` creation) |
| `to_status` | `VARCHAR(32)` | `NOT NULL` | New permit status after transition |
| `policy_applied` | `VARCHAR(64)` | `NOT NULL` | Policy/action code (`PROJECT_INIT`, `BUNDLED`, `MAIN_FIRST`, `BOTTOM_UP`, `TOP_DOWN`, `INDEPENDENT`, `SYNCHRONIZED`, `DECOUPLED`) |
| `actor_id` | `UUID` | `NOT NULL` | Caller actor UUID from request |
| `comments` | `TEXT` | `NOT NULL` | Trimmed non-blank justification / review note |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Audit entry timestamp |

---

## 3. Indexing Strategy

| Index Name | Table & Columns | Purpose |
| :--- | :--- | :--- |
| `idx_jwc_jurisdiction_current` | `jurisdiction_workflow_configs (jurisdiction_id, is_current)` | Accelerates active config lookup during `POST /projects` and `GET /config` |
| `idx_projects_jurisdiction` | `projects (jurisdiction_id)` | Supports municipal reporting and tenant filtering |
| `idx_permits_project` | `permits (project_id)` | Accelerates loading all sibling permits under project lock |
| `idx_permits_parent` | `permits (parent_permit_id)` | Accelerates parent-child hierarchy traversal |
| `idx_history_permit_created` | `permit_status_history (permit_id, created_at)` | Serves `GET /permits/{permitId}/history` sorted `ASC` without filesort |

---

## 4. Flyway Migration Chain & Hibernate Validation

* **Strict Schema Ownership:** Hibernate is configured with `spring.jpa.hibernate.ddl-auto: validate` so the ORM never alters DDL silently; any entity-to-SQL drift fails fast at startup.
* **Migration Sequence:**
   1. `V1__init_schema.sql`: Provisions all 7 tables, check constraints, foreign keys, and indexes.
   2. `V2__seed_jurisdictions.sql`: Inserts deterministic UUIDs for 5 global `permit_types`, **City of Tampa** (`11111111-1111-1111-1111-111111111111`), and **City of Austin** (`33333333-3333-3333-3333-333333333333`).
* **Cross-Dialect Compatibility:** Embedded H2 runs with `jdbc:h2:mem:blitzpermits;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` so PostgreSQL SQL syntax (`TIMESTAMP WITH TIME ZONE`, `SELECT ... FOR UPDATE`, UUID literals) executes identically in local/test runs and production PostgreSQL.

---

## 5. Two-Tier Concurrency & Locking Protocol

Multi-permit workflows suffer from **cross-row invariant race conditions** if two sibling permits are mutated concurrently (for example, approving a `SUB` permit while another thread simultaneously approves the `MAIN` permit under `BOTTOM_UP`, or issuing corrections on Permit A while approving Permit B under `SYNCHRONIZED`).

To eliminate race conditions without deadlocks, every mutating method in `PermitLifecycleService` enforces a strict **canonical lock acquisition order**:

```text
Thread T1 (approve SUB)               Thread T2 (approve MAIN)
         │                                       │
         ├─ 1. Validate non-blank comments       ├─ 1. Validate non-blank comments
         ├─ 2. Resolve projectId from permitId   ├─ 2. Resolve projectId from permitId
         ├─ 3. SELECT * FROM projects            ├─ 3. SELECT * FROM projects
         │     WHERE id = ? FOR UPDATE           │     WHERE id = ? FOR UPDATE
         │     [ACQUIRES ROW LOCK]               │     [BLOCKS UNTIL T1 COMMITS]
         ├─ 4. Load permits & evaluate policy    │
         ├─ 5. Mutate permit & append history    │
         └─ 6. COMMIT (releases lock) ──────────►├─ 4. Loads fresh committed state
                                                 ├─ 5. Evaluates policy accurately
                                                 └─ 6. COMMIT
```

### Key Concurrency Guarantees
1. **Single Aggregate Lock Root (`Project`):** All transactions lock the parent `projects` row first via `ProjectRepository.findByIdWithLock(UUID id)` (`@Lock(LockModeType.PESSIMISTIC_WRITE)`), preventing circular lock-ordering deadlocks across child `permits` rows.
2. **Pre-Lock Input Validation:** Blank `comments` validation executes *before* acquiring the `PESSIMISTIC_WRITE` lock so malformed `400 Bad Request` calls never hold database locks.
3. **Secondary Optimistic Lock (`Permit.version`):** Every `Permit` row carries a JPA `@Version` counter (`BIGINT`), protecting against stale detached entity writes and mapping `OptimisticLockingFailureException` to HTTP `409 Conflict`.
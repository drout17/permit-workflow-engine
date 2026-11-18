-- ============================================================================
-- 1. MASTER PERMIT CATALOG (Normalized Global Lookup)
-- ============================================================================

CREATE TABLE permit_types (
                              id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                              code VARCHAR(50) NOT NULL UNIQUE,
                              name VARCHAR(150) NOT NULL,
                              description VARCHAR(500),
                              is_active BOOLEAN NOT NULL DEFAULT TRUE,
                              created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 2. CONFIGURATION DOMAIN (Jurisdiction Rules & Permit Mappings)
-- ============================================================================

CREATE TABLE jurisdictions (
                               id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                               name VARCHAR(150) NOT NULL,
                               state_code VARCHAR(10) NOT NULL,
                               is_active BOOLEAN NOT NULL DEFAULT TRUE,
                               created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE jurisdiction_workflow_configs (
                                               id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                                               jurisdiction_id UUID NOT NULL,
                                               version_number INT NOT NULL,
                                               is_current BOOLEAN NOT NULL DEFAULT FALSE,
                                               submission_mode VARCHAR(30) NOT NULL CHECK (submission_mode IN ('BUNDLED', 'MAIN_FIRST')),
                                               approval_mode VARCHAR(30) NOT NULL CHECK (approval_mode IN ('BOTTOM_UP', 'TOP_DOWN', 'INDEPENDENT')),
                                               resubmission_mode VARCHAR(30) NOT NULL CHECK (resubmission_mode IN ('SYNCHRONIZED', 'DECOUPLED')),
                                               custom_rules_json CLOB,
                                               created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               CONSTRAINT fk_config_jurisdiction FOREIGN KEY (jurisdiction_id) REFERENCES jurisdictions(id) ON DELETE CASCADE,
                                               CONSTRAINT uq_jurisdiction_config_version UNIQUE (jurisdiction_id, version_number)
);

-- Join/Mapping Table: Attaches global permit_types to a jurisdiction config with city-specific roles
CREATE TABLE jurisdiction_permit_mappings (
                                              id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                                              config_id UUID NOT NULL,
                                              permit_type_id UUID NOT NULL,
                                              hierarchy_role VARCHAR(20) NOT NULL CHECK (hierarchy_role IN ('MAIN', 'SUB')),
                                              is_mandatory BOOLEAN NOT NULL DEFAULT FALSE,
                                              CONSTRAINT fk_mapping_config FOREIGN KEY (config_id) REFERENCES jurisdiction_workflow_configs(id) ON DELETE CASCADE,
                                              CONSTRAINT fk_mapping_permit_type FOREIGN KEY (permit_type_id) REFERENCES permit_types(id) ON DELETE RESTRICT,
                                              CONSTRAINT uq_config_permit_type UNIQUE (config_id, permit_type_id)
);

-- ============================================================================
-- 3. OPERATIONAL / RUNTIME DOMAIN (Projects & Permits)
-- ============================================================================

CREATE TABLE projects (
                          id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                          applicant_id UUID NOT NULL,
                          jurisdiction_id UUID NOT NULL,
                          applied_config_id UUID NOT NULL,
                          status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
                          created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT fk_project_jurisdiction FOREIGN KEY (jurisdiction_id) REFERENCES jurisdictions(id),
                          CONSTRAINT fk_project_config FOREIGN KEY (applied_config_id) REFERENCES jurisdiction_workflow_configs(id)
);

CREATE TABLE permits (
                         id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                         project_id UUID NOT NULL,
                         parent_permit_id UUID,
                         permit_type_id UUID NOT NULL,
                         status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK (
                             status IN (
                                        'DRAFT',
                                        'SUBMITTED',
                                        'IN_REVIEW',
                                        'CORRECTIONS_REQUIRED',
                                        'ON_HOLD_FOR_SIBLINGS',
                                        'APPROVED'
                                 )
                             ),
                         resubmission_cycle INT NOT NULL DEFAULT 0,
                         version BIGINT NOT NULL DEFAULT 0,
                         updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         CONSTRAINT fk_permit_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                         CONSTRAINT fk_permit_parent FOREIGN KEY (parent_permit_id) REFERENCES permits(id) ON DELETE CASCADE,
                         CONSTRAINT fk_permit_type FOREIGN KEY (permit_type_id) REFERENCES permit_types(id) ON DELETE RESTRICT,
                         CONSTRAINT uq_project_permit_type UNIQUE (project_id, permit_type_id)
);

CREATE TABLE permit_status_history (
                                       id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
                                       permit_id UUID NOT NULL,
                                       from_status VARCHAR(30),
                                       to_status VARCHAR(30) NOT NULL,
                                       policy_applied VARCHAR(100),
                                       actor_id UUID NOT NULL,
                                       comments TEXT,
                                       created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                       CONSTRAINT fk_history_permit FOREIGN KEY (permit_id) REFERENCES permits(id) ON DELETE CASCADE
);

-- ============================================================================
-- 4. PERFORMANCE INDEXES
-- ============================================================================

CREATE INDEX idx_mappings_config_id ON jurisdiction_permit_mappings(config_id);
CREATE INDEX idx_mappings_permit_type_id ON jurisdiction_permit_mappings(permit_type_id);
CREATE INDEX idx_projects_jurisdiction ON projects(jurisdiction_id);
CREATE INDEX idx_projects_applicant ON projects(applicant_id);
CREATE INDEX idx_permits_project_id ON permits(project_id);
CREATE INDEX idx_permits_parent_id ON permits(parent_permit_id);
CREATE INDEX idx_permits_status ON permits(status);
CREATE INDEX idx_permit_history_permit_id ON permit_status_history(permit_id);
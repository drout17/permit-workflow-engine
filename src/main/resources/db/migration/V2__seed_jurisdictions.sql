-- ============================================================================
-- 1. SEED MASTER PERMIT CATALOG (Defined Once Globally)
-- ============================================================================
INSERT INTO permit_types (id, code, name, description) VALUES
                                                           ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'NEW_CONSTRUCTION', 'New Building Construction', 'Primary structural building permit'),
                                                           ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'ELECTRICAL', 'Electrical Permit', 'Electrical wiring, panels, and service'),
                                                           ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3', 'PLUMBING', 'Plumbing Permit', 'Water supply, drainage, and piping'),
                                                           ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4', 'MECHANICAL', 'Mechanical / HVAC Permit', 'Heating, ventilation, and air conditioning'),
                                                           ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5', 'SOLAR', 'Solar PV Installation Permit', 'Rooftop or ground-mounted solar array');

-- ============================================================================
-- 2. SEED JURISDICTION 1: City of Tampa (Bundled + Bottom-Up + Synchronized)
-- ============================================================================
INSERT INTO jurisdictions (id, name, state_code, is_active)
VALUES ('11111111-1111-1111-1111-111111111111', 'City of Tampa', 'FL', TRUE);

INSERT INTO jurisdiction_workflow_configs (
    id, jurisdiction_id, version_number, is_current,
    submission_mode, approval_mode, resubmission_mode, custom_rules_json
)
VALUES (
           '22222222-2222-2222-2222-222222222222',
           '11111111-1111-1111-1111-111111111111',
           1, TRUE,
           'BUNDLED', 'BOTTOM_UP', 'SYNCHRONIZED',
           '{"description": "All permits submitted together; all sub-permits must be approved before main permit; resubmissions happen together."}'
       );

-- Tampa maps NEW_CONSTRUCTION as MAIN (Mandatory), and ELECTRICAL/PLUMBING/SOLAR as SUB
INSERT INTO jurisdiction_permit_mappings (id, config_id, permit_type_id, hierarchy_role, is_mandatory) VALUES
                                                                                                           (RANDOM_UUID(), '22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'MAIN', TRUE),
                                                                                                           (RANDOM_UUID(), '22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'SUB', FALSE),
                                                                                                           (RANDOM_UUID(), '22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3', 'SUB', TRUE),
                                                                                                           (RANDOM_UUID(), '22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5', 'SUB', FALSE);

-- ============================================================================
-- 3. SEED JURISDICTION 2: City of Austin (Main-First + Top-Down + Decoupled)
-- ============================================================================
INSERT INTO jurisdictions (id, name, state_code, is_active)
VALUES ('33333333-3333-3333-3333-333333333333', 'City of Austin', 'TX', TRUE);

INSERT INTO jurisdiction_workflow_configs (
    id, jurisdiction_id, version_number, is_current,
    submission_mode, approval_mode, resubmission_mode, custom_rules_json
)
VALUES (
           '44444444-4444-4444-4444-444444444444',
           '33333333-3333-3333-3333-333333333333',
           1, TRUE,
           'MAIN_FIRST', 'TOP_DOWN', 'DECOUPLED',
           '{"description": "Main permit applied first; main permit approved before sub-permits; each permit resubmits independently."}'
       );

-- Austin maps ELECTRICAL as MAIN (Mandatory) and SOLAR/MECHANICAL as SUB
INSERT INTO jurisdiction_permit_mappings (id, config_id, permit_type_id, hierarchy_role, is_mandatory) VALUES
                                                                                                           (RANDOM_UUID(), '44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'MAIN', TRUE),
                                                                                                           (RANDOM_UUID(), '44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4', 'SUB', FALSE),
                                                                                                    (RANDOM_UUID(), '44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5', 'SUB', FALSE);
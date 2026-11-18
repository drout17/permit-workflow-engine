package ai.blitzpermits.permitworkflowengine.service;

import ai.blitzpermits.permitworkflowengine.engine.PolicyRegistry;
import ai.blitzpermits.permitworkflowengine.entity.Jurisdiction;
import ai.blitzpermits.permitworkflowengine.entity.JurisdictionPermitMapping;
import ai.blitzpermits.permitworkflowengine.entity.JurisdictionWorkflowConfig;
import ai.blitzpermits.permitworkflowengine.exception.ResourceNotFoundException;
import ai.blitzpermits.permitworkflowengine.repository.JurisdictionPermitMappingRepository;
import ai.blitzpermits.permitworkflowengine.repository.JurisdictionRepository;
import ai.blitzpermits.permitworkflowengine.repository.JurisdictionWorkflowConfigRepository;
import ai.blitzpermits.permitworkflowengine.repository.PermitTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JurisdictionConfigService {

    private final JurisdictionRepository jurisdictionRepository;
    private final JurisdictionWorkflowConfigRepository configRepository;
    private final JurisdictionPermitMappingRepository mappingRepository;
    private final PermitTypeRepository permitTypeRepository;
    private final PolicyRegistry policyRegistry;

    public record PermitMappingSpec(
            String permitTypeCode,
            String hierarchyRole,
            boolean isMandatory
    ) {}

    @Transactional(readOnly = true)
    public List<Jurisdiction> getActiveJurisdictions() {
        return jurisdictionRepository.findByIsActiveTrue();
    }

    @Transactional(readOnly = true)
    public JurisdictionWorkflowConfig getCurrentConfig(UUID jurisdictionId) {
        return configRepository.findByJurisdictionIdAndIsCurrentTrue(jurisdictionId)
                .orElseThrow(() -> new ResourceNotFoundException("No active workflow config found for jurisdiction: " + jurisdictionId));
    }

    @Transactional(readOnly = true)
    public List<JurisdictionPermitMapping> getConfigMappings(UUID configId) {
        return mappingRepository.findByConfigId(configId);
    }

    @Transactional
    public JurisdictionWorkflowConfig publishNewConfigVersion(
            UUID jurisdictionId,
            String submissionMode,
            String approvalMode,
            String resubmissionMode,
            List<PermitMappingSpec> mappingSpecs
    ) {
        // Validate that every mode string resolves to a registered strategy bean
        policyRegistry.getSubmissionPolicy(submissionMode);
        policyRegistry.getApprovalPolicy(approvalMode);
        policyRegistry.getResubmissionPolicy(resubmissionMode);

        long mainCount = mappingSpecs.stream()
                .filter(spec -> "MAIN".equals(spec.hierarchyRole()))
                .count();
        if (mainCount != 1) {
            throw new IllegalArgumentException("A workflow configuration must define exactly one MAIN permit.");
        }

        var jurisdiction = jurisdictionRepository.findById(jurisdictionId)
                .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + jurisdictionId));

        int nextVersion = switch (configRepository.findByJurisdictionIdAndIsCurrentTrue(jurisdictionId).orElse(null)) {
            case JurisdictionWorkflowConfig existing -> {
                existing.setIsCurrent(false);
                configRepository.save(existing);
                yield existing.getVersionNumber() + 1;
            }
            case null -> 1;
        };

        var newConfig = configRepository.save(JurisdictionWorkflowConfig.builder()
                .jurisdiction(jurisdiction)
                .versionNumber(nextVersion)
                .submissionMode(submissionMode)
                .approvalMode(approvalMode)
                .resubmissionMode(resubmissionMode)
                .isCurrent(true)
                .build());

        for (var spec : mappingSpecs) {
            var permitType = permitTypeRepository.findByCode(spec.permitTypeCode())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permit type code: " + spec.permitTypeCode()));

            mappingRepository.save(JurisdictionPermitMapping.builder()
                    .config(newConfig)
                    .permitType(permitType)
                    .hierarchyRole(spec.hierarchyRole())
                    .isMandatory(spec.isMandatory())
                    .build());
        }

        return newConfig;
    }

    @Transactional
    public Jurisdiction createJurisdiction(String name, String stateCode) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Jurisdiction name is required.");
        }
        if (stateCode == null || stateCode.length() != 2) {
            throw new IllegalArgumentException("State code must be a 2-letter code (e.g., FL, TX, CA).");
        }

        return jurisdictionRepository.save(Jurisdiction.builder()
                .name(name.trim())
                .stateCode(stateCode.trim().toUpperCase())
                .isActive(true)
                .build());
    }
}
package ai.blitzpermits.permitworkflowengine.dto;

import java.util.List;

public record PublishWorkflowConfigRequest(
        String submissionMode,
        String approvalMode,
        String resubmissionMode,
        List<PermitMappingDto> mappings
) {
    public record PermitMappingDto(
            String permitTypeCode,
            String hierarchyRole,
            boolean isMandatory
    ) {}
}
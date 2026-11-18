package ai.blitzpermits.permitworkflowengine.dto;

import ai.blitzpermits.permitworkflowengine.entity.JurisdictionWorkflowConfig;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import ai.blitzpermits.permitworkflowengine.entity.Project;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProjectDetailsResponse(
        UUID id,
        UUID applicantId,
        UUID jurisdictionId,
        String jurisdictionName,
        String status,
        WorkflowModesDto appliedPolicy,
        List<PermitResponse> permits,
        OffsetDateTime createdAt
) {
    public record WorkflowModesDto(
            Integer versionNumber,
            String submissionMode,
            String approvalMode,
            String resubmissionMode
    ) {}

    public static ProjectDetailsResponse from(Project project, List<Permit> permits) {
        JurisdictionWorkflowConfig cfg = project.getAppliedConfig();
        return new ProjectDetailsResponse(
                project.getId(),
                project.getApplicantId(),
                project.getJurisdiction().getId(),
                project.getJurisdiction().getName(),
                project.getStatus(),
                new WorkflowModesDto(
                        cfg.getVersionNumber(),
                        cfg.getSubmissionMode(),
                        cfg.getApprovalMode(),
                        cfg.getResubmissionMode()
                ),
                permits.stream().map(PermitResponse::from).toList(),
                project.getCreatedAt()
        );
    }
}
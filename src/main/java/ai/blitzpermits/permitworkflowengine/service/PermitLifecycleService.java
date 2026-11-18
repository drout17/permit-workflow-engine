package ai.blitzpermits.permitworkflowengine.service;

import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.engine.PolicyRegistry;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import ai.blitzpermits.permitworkflowengine.entity.PermitStatusHistory;
import ai.blitzpermits.permitworkflowengine.entity.Project;
import ai.blitzpermits.permitworkflowengine.exception.WorkflowRuleViolationException;
import ai.blitzpermits.permitworkflowengine.repository.JurisdictionPermitMappingRepository;
import ai.blitzpermits.permitworkflowengine.repository.JurisdictionRepository;
import ai.blitzpermits.permitworkflowengine.repository.JurisdictionWorkflowConfigRepository;
import ai.blitzpermits.permitworkflowengine.repository.PermitRepository;
import ai.blitzpermits.permitworkflowengine.repository.PermitStatusHistoryRepository;
import ai.blitzpermits.permitworkflowengine.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermitLifecycleService {

    private final JurisdictionRepository jurisdictionRepository;
    private final JurisdictionWorkflowConfigRepository configRepository;
    private final JurisdictionPermitMappingRepository mappingRepository;
    private final ProjectRepository projectRepository;
    private final PermitRepository permitRepository;
    private final PermitStatusHistoryRepository historyRepository;
    private final PolicyRegistry policyRegistry;

    @Transactional
    public Project createProject(UUID applicantId, UUID jurisdictionId, List<UUID> selectedOptionalPermitTypeIds) {
        var jurisdiction = jurisdictionRepository.findById(jurisdictionId)
                .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + jurisdictionId));

        var activeConfig = configRepository.findByJurisdictionIdAndIsCurrentTrue(jurisdictionId)
                .orElseThrow(() -> new IllegalStateException("No active workflow config for jurisdiction: " + jurisdictionId));

        var mappings = mappingRepository.findByConfigId(activeConfig.getId());

        var project = projectRepository.save(Project.builder()
                .applicantId(applicantId)
                .jurisdiction(jurisdiction)
                .appliedConfig(activeConfig)
                .status("DRAFT")
                .build());

        var mainMapping = mappings.stream()
                .filter(m -> "MAIN".equals(m.getHierarchyRole()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Active config has no MAIN permit defined."));

        var mainPermit = permitRepository.save(Permit.builder()
                .project(project)
                .parentPermit(null)
                .permitType(mainMapping.getPermitType())
                .status("DRAFT")
                .build());

        recordHistory(mainPermit, null, "DRAFT", "PROJECT_INIT", applicantId, "Project initialized in DRAFT status.");

        var optionalSelections = Objects.requireNonNullElse(selectedOptionalPermitTypeIds, List.<UUID>of());
        for (var mapping : mappings) {
            if ("SUB".equals(mapping.getHierarchyRole())
                    && (Boolean.TRUE.equals(mapping.getIsMandatory()) || optionalSelections.contains(mapping.getPermitType().getId()))) {
                var subPermit = permitRepository.save(Permit.builder()
                        .project(project)
                        .parentPermit(mainPermit)
                        .permitType(mapping.getPermitType())
                        .status("DRAFT")
                        .build());
                recordHistory(subPermit, null, "DRAFT", "PROJECT_INIT", applicantId, "Project initialized in DRAFT status.");
            }
        }

        return project;
    }

    @Transactional
    public List<Permit> submitProjectBundle(UUID projectId, UUID actorId, String comments) {
        validateMandatoryComments(comments, "submitting a project bundle for");

        var project = lockProject(projectId);
        var allPermits = permitRepository.findByProjectId(projectId);
        var submissionMode = project.getAppliedConfig().getSubmissionMode();

        var draftPermits = allPermits.stream()
                .filter(p -> "DRAFT".equals(p.getStatus()))
                .toList();

        if (draftPermits.isEmpty()) {
            throw new WorkflowRuleViolationException("No DRAFT permits available to submit.");
        }

        for (var permit : draftPermits) {
            var previousStatus = permit.getStatus();
            permit.setStatus("SUBMITTED");
            permitRepository.save(permit);
            recordHistory(permit, previousStatus, "SUBMITTED", submissionMode, actorId, comments.trim());
        }

        project.setStatus("ACTIVE");
        projectRepository.save(project);
        return allPermits;
    }

    @Transactional
    public Permit submitSinglePermit(UUID permitId, UUID actorId, String comments) {
        validateMandatoryComments(comments, "submitting");

        var target = permitRepository.findById(permitId)
                .orElseThrow(() -> new IllegalArgumentException("Permit not found: " + permitId));
        var project = lockProject(target.getProject().getId());
        var allPermits = permitRepository.findByProjectId(project.getId());

        var mode = project.getAppliedConfig().getSubmissionMode();
        enforcePolicy(policyRegistry.getSubmissionPolicy(mode).evaluate(target, allPermits));

        var previousStatus = target.getStatus();
        target.setStatus("SUBMITTED");
        permitRepository.save(target);
        recordHistory(target, previousStatus, "SUBMITTED", mode, actorId, comments.trim());

        if ("DRAFT".equals(project.getStatus())) {
            project.setStatus("ACTIVE");
            projectRepository.save(project);
        }

        return target;
    }

    @Transactional
    public Permit approvePermit(UUID permitId, UUID actorId, String comments) {
        validateMandatoryComments(comments, "approving");

        var target = permitRepository.findById(permitId)
                .orElseThrow(() -> new IllegalArgumentException("Permit not found: " + permitId));
        var project = lockProject(target.getProject().getId());
        var allPermits = permitRepository.findByProjectId(project.getId());

        if ("APPROVED".equals(target.getStatus())) {
            throw new WorkflowRuleViolationException("Permit is already APPROVED.");
        }

        var mode = project.getAppliedConfig().getApprovalMode();
        enforcePolicy(policyRegistry.getApprovalPolicy(mode).evaluate(target, allPermits));

        var previousStatus = target.getStatus();
        target.setStatus("APPROVED");
        permitRepository.save(target);
        recordHistory(target, previousStatus, "APPROVED", mode, actorId, comments.trim());

        if (allPermits.stream().allMatch(p -> "APPROVED".equals(p.getStatus()))) {
            project.setStatus("COMPLETED");
            projectRepository.save(project);
        }

        return target;
    }

    @Transactional
    public List<Permit> requestCorrections(UUID permitId, UUID actorId, String comments) {
        validateMandatoryComments(comments, "requesting corrections on");

        var target = permitRepository.findById(permitId)
                .orElseThrow(() -> new IllegalArgumentException("Permit not found: " + permitId));
        var project = lockProject(target.getProject().getId());
        var allPermits = permitRepository.findByProjectId(project.getId());

        var mode = project.getAppliedConfig().getResubmissionMode();
        var policy = policyRegistry.getResubmissionPolicy(mode);

        var previousStatus = target.getStatus();
        target.setStatus("CORRECTIONS_REQUIRED");
        permitRepository.save(target);
        recordHistory(target, previousStatus, "CORRECTIONS_REQUIRED", mode, actorId, comments.trim());

        var affectedSiblings = new ArrayList<Permit>();
        for (var sibling : allPermits) {
            if (!sibling.getId().equals(target.getId())
                    && !List.of("DRAFT", "CORRECTIONS_REQUIRED", "ON_HOLD_FOR_SIBLINGS").contains(sibling.getStatus())) {
                var siblingPrevStatus = sibling.getStatus();
                policy.onCorrectionsRequested(target, List.of(target, sibling));
                if (!siblingPrevStatus.equals(sibling.getStatus())) {
                    permitRepository.save(sibling);
                    recordHistory(
                            sibling,
                            siblingPrevStatus,
                            sibling.getStatus(),
                            mode,
                            actorId,
                            "Auto-placed on hold while sibling permit undergoes corrections."
                    );
                    affectedSiblings.add(sibling);
                }
            }
        }

        var updated = new ArrayList<Permit>(1 + affectedSiblings.size());
        updated.add(target);
        updated.addAll(affectedSiblings);
        return List.copyOf(updated);
    }

    @Transactional
    public Permit resubmitPermit(UUID permitId, UUID actorId, String comments) {
        validateMandatoryComments(comments, "resubmitting");

        var target = permitRepository.findById(permitId)
                .orElseThrow(() -> new IllegalArgumentException("Permit not found: " + permitId));
        var project = lockProject(target.getProject().getId());
        var allPermits = permitRepository.findByProjectId(project.getId());

        var mode = project.getAppliedConfig().getResubmissionMode();
        enforcePolicy(policyRegistry.getResubmissionPolicy(mode).evaluateResubmission(target, allPermits));

        var previousStatus = target.getStatus();
        target.setStatus("SUBMITTED");
        target.setResubmissionCycle(target.getResubmissionCycle() + 1);
        permitRepository.save(target);
        recordHistory(target, previousStatus, "SUBMITTED", mode, actorId, comments.trim());

        return target;
    }

    private void enforcePolicy(PolicyDecision decision) {
        switch (decision) {
            case PolicyDecision.Allowed() -> {}
            case PolicyDecision.Denied(var reason) -> throw new WorkflowRuleViolationException(reason);
        }
    }

    private Project lockProject(UUID projectId) {
        return projectRepository.findByIdWithLock(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    private void validateMandatoryComments(String comments, String actionName) {
        if (comments == null || comments.isBlank()) {
            throw new IllegalArgumentException("Comments are mandatory when " + actionName + " a permit.");
        }
    }

    private void recordHistory(
            Permit permit,
            String fromStatus,
            String toStatus,
            String policyApplied,
            UUID actorId,
            String comments
    ) {
        historyRepository.save(PermitStatusHistory.builder()
                .permit(permit)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .policyApplied(policyApplied)
                .actorId(actorId)
                .comments(comments)
                .build());
    }
}
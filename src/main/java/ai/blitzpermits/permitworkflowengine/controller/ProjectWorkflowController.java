package ai.blitzpermits.permitworkflowengine.controller;

import ai.blitzpermits.permitworkflowengine.dto.CreateProjectRequest;
import ai.blitzpermits.permitworkflowengine.dto.PermitActionRequest;
import ai.blitzpermits.permitworkflowengine.dto.PermitHistoryResponse;
import ai.blitzpermits.permitworkflowengine.dto.PermitResponse;
import ai.blitzpermits.permitworkflowengine.dto.ProjectDetailsResponse;
import ai.blitzpermits.permitworkflowengine.repository.PermitRepository;
import ai.blitzpermits.permitworkflowengine.repository.PermitStatusHistoryRepository;
import ai.blitzpermits.permitworkflowengine.repository.ProjectRepository;
import ai.blitzpermits.permitworkflowengine.service.PermitLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Project & Permit Workflows", description = "Create permit projects, execute policy-enforced state transitions, and inspect audit history")
public class ProjectWorkflowController {

    private final PermitLifecycleService lifecycleService;
    private final ProjectRepository projectRepository;
    private final PermitRepository permitRepository;
    private final PermitStatusHistoryRepository historyRepository;

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new permit project", description = "Instantiates a project pinned to the jurisdiction's active workflow version along with its MAIN and mandatory/selected SUB permits in DRAFT status.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Project and initial permits created"),
            @ApiResponse(responseCode = "400", description = "Invalid jurisdiction ID")
    })
    public ProjectDetailsResponse createProject(@RequestBody CreateProjectRequest request) {
        var project = lifecycleService.createProject(
                request.applicantId(),
                request.jurisdictionId(),
                request.selectedOptionalPermitTypeIds()
        );
        var permits = permitRepository.findByProjectId(project.getId());
        return ProjectDetailsResponse.from(project, permits);
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "Get project details and permit tree", description = "Returns project metadata, pinned workflow policy modes, and all associated MAIN/SUB permits.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project details returned"),
            @ApiResponse(responseCode = "400", description = "Project not found")
    })
    public ProjectDetailsResponse getProject(@PathVariable UUID projectId) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        var permits = permitRepository.findByProjectId(projectId);
        return ProjectDetailsResponse.from(project, permits);
    }

    @PostMapping("/projects/{projectId}/submit-bundle")
    @Operation(summary = "Submit all DRAFT permits as a bundle", description = "Transitions all DRAFT permits in the project to SUBMITTED simultaneously.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bundle submitted successfully"),
            @ApiResponse(responseCode = "400", description = "Missing mandatory comments or invalid project ID"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification conflict"),
            @ApiResponse(responseCode = "422", description = "No DRAFT permits available to submit")
    })
    public ProjectDetailsResponse submitProjectBundle(
            @PathVariable UUID projectId,
            @RequestBody PermitActionRequest request
    ) {
        lifecycleService.submitProjectBundle(projectId, request.actorId(), request.comments());
        return getProject(projectId);
    }

    @PostMapping("/permits/{permitId}/submit")
    @Operation(summary = "Submit an individual permit", description = "Evaluates the project's SubmissionPolicy (e.g., BUNDLED, MAIN_FIRST) before transitioning the permit to SUBMITTED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permit submitted"),
            @ApiResponse(responseCode = "400", description = "Missing mandatory comments or invalid permit ID"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification conflict"),
            @ApiResponse(responseCode = "422", description = "Blocked by jurisdiction submission policy")
    })
    public PermitResponse submitPermit(
            @PathVariable UUID permitId,
            @RequestBody PermitActionRequest request
    ) {
        var updated = lifecycleService.submitSinglePermit(permitId, request.actorId(), request.comments());
        return PermitResponse.from(updated);
    }

    @PostMapping("/permits/{permitId}/approve")
    @Operation(summary = "Approve a permit", description = "Evaluates the project's ApprovalPolicy (BOTTOM_UP, TOP_DOWN, INDEPENDENT) before transitioning the permit to APPROVED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permit approved"),
            @ApiResponse(responseCode = "400", description = "Missing mandatory comments or invalid permit ID"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification conflict"),
            @ApiResponse(responseCode = "422", description = "Blocked by jurisdiction approval policy")
    })
    public PermitResponse approvePermit(
            @PathVariable UUID permitId,
            @RequestBody PermitActionRequest request
    ) {
        var updated = lifecycleService.approvePermit(permitId, request.actorId(), request.comments());
        return PermitResponse.from(updated);
    }

    @PostMapping("/permits/{permitId}/request-corrections")
    @Operation(summary = "Request corrections on a permit", description = "Moves target permit to CORRECTIONS_REQUIRED and places sibling permits on ON_HOLD_FOR_SIBLINGS if SYNCHRONIZED resubmission is configured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Corrections requested and affected permits returned"),
            @ApiResponse(responseCode = "400", description = "Missing mandatory comments or invalid permit ID"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification conflict")
    })
    public List<PermitResponse> requestCorrections(
            @PathVariable UUID permitId,
            @RequestBody PermitActionRequest request
    ) {
        return lifecycleService.requestCorrections(permitId, request.actorId(), request.comments()).stream()
                .map(PermitResponse::from)
                .toList();
    }

    @PostMapping("/permits/{permitId}/resubmit")
    @Operation(summary = "Resubmit a permit after corrections", description = "Evaluates the project's ResubmissionPolicy (SYNCHRONIZED, DECOUPLED), increments resubmissionCycle, and moves status back to SUBMITTED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permit resubmitted"),
            @ApiResponse(responseCode = "400", description = "Missing mandatory comments or invalid permit ID"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification conflict"),
            @ApiResponse(responseCode = "422", description = "Blocked by jurisdiction resubmission policy")
    })
    public PermitResponse resubmitPermit(
            @PathVariable UUID permitId,
            @RequestBody PermitActionRequest request
    ) {
        var updated = lifecycleService.resubmitPermit(permitId, request.actorId(), request.comments());
        return PermitResponse.from(updated);
    }

    @GetMapping("/permits/{permitId}/history")
    @Operation(summary = "Get permit audit trail", description = "Returns chronological status transitions, applied policies, actor IDs, and comments for a permit.")
    public List<PermitHistoryResponse> getPermitHistory(@PathVariable UUID permitId) {
        return historyRepository.findByPermitIdOrderByCreatedAtAsc(permitId).stream()
                .map(PermitHistoryResponse::from)
                .toList();
    }
}
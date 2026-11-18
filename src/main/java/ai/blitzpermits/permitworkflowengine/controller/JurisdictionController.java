package ai.blitzpermits.permitworkflowengine.controller;

import ai.blitzpermits.permitworkflowengine.dto.CreateJurisdictionRequest;
import ai.blitzpermits.permitworkflowengine.dto.PublishWorkflowConfigRequest;
import ai.blitzpermits.permitworkflowengine.entity.JurisdictionPermitMapping;
import ai.blitzpermits.permitworkflowengine.entity.JurisdictionWorkflowConfig;
import ai.blitzpermits.permitworkflowengine.service.JurisdictionConfigService;
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
@RequestMapping("/api/v1/jurisdictions")
@RequiredArgsConstructor
@Tag(name = "Jurisdiction Configurations", description = "Manage active jurisdictions and versioned workflow policies")
public class JurisdictionController {

    private final JurisdictionConfigService configService;

    public record JurisdictionSummary(UUID id, String name, String stateCode) {}

    public record ActiveConfigResponse(
            UUID configId,
            Integer versionNumber,
            String submissionMode,
            String approvalMode,
            String resubmissionMode,
            List<MappingItem> permitMappings
    ) {
        public record MappingItem(
                UUID permitTypeId,
                String permitTypeCode,
                String permitTypeName,
                String hierarchyRole,
                Boolean isMandatory
        ) {}
    }

    @GetMapping
    @Operation(summary = "List active jurisdictions", description = "Returns all jurisdictions enabled for permit submissions.")
    public List<JurisdictionSummary> listActiveJurisdictions() {
        return configService.getActiveJurisdictions().stream()
                .map(j -> new JurisdictionSummary(j.getId(), j.getName(), j.getStateCode()))
                .toList();
    }

    @GetMapping("/{jurisdictionId}/config")
    @Operation(summary = "Get active workflow configuration", description = "Fetches the current workflow strategy modes and MAIN/SUB permit mappings for a jurisdiction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active configuration returned"),
            @ApiResponse(responseCode = "400", description = "Jurisdiction or active configuration not found")
    })
    public ActiveConfigResponse getCurrentConfig(@PathVariable UUID jurisdictionId) {
        var cfg = configService.getCurrentConfig(jurisdictionId);
        var mappings = configService.getConfigMappings(cfg.getId());

        return new ActiveConfigResponse(
                cfg.getId(),
                cfg.getVersionNumber(),
                cfg.getSubmissionMode(),
                cfg.getApprovalMode(),
                cfg.getResubmissionMode(),
                mappings.stream()
                        .map(m -> new ActiveConfigResponse.MappingItem(
                                m.getPermitType().getId(),
                                m.getPermitType().getCode(),
                                m.getPermitType().getName(),
                                m.getHierarchyRole(),
                                m.getIsMandatory()
                        ))
                        .toList()
        );
    }

    @PostMapping("/{jurisdictionId}/config")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publish new workflow configuration version", description = "Creates a new active policy version without affecting in-flight projects pinned to prior versions.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "New workflow configuration version published"),
            @ApiResponse(responseCode = "400", description = "Invalid policy mode, missing MAIN permit, or unknown permit type code")
    })
    public ActiveConfigResponse publishNewConfig(
            @PathVariable UUID jurisdictionId,
            @RequestBody PublishWorkflowConfigRequest request
    ) {
        var specs = request.mappings().stream()
                .map(m -> new JurisdictionConfigService.PermitMappingSpec(
                        m.permitTypeCode(),
                        m.hierarchyRole(),
                        m.isMandatory()
                ))
                .toList();

        var saved = configService.publishNewConfigVersion(
                jurisdictionId,
                request.submissionMode(),
                request.approvalMode(),
                request.resubmissionMode(),
                specs
        );

        return getCurrentConfig(saved.getJurisdiction().getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new jurisdiction", description = "Registers a new active municipality so workflow configurations can be published for it.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Jurisdiction created"),
            @ApiResponse(responseCode = "400", description = "Invalid name or state code")
    })
    public JurisdictionSummary createJurisdiction(@RequestBody CreateJurisdictionRequest request) {
        var created = configService.createJurisdiction(request.name(), request.stateCode());
        return new JurisdictionSummary(created.getId(), created.getName(), created.getStateCode());
    }
}
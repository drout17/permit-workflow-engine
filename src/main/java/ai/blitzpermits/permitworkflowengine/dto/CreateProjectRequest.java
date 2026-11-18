package ai.blitzpermits.permitworkflowengine.dto;

import java.util.List;
import java.util.UUID;

public record CreateProjectRequest(
        UUID applicantId,
        UUID jurisdictionId,
        List<UUID> selectedOptionalPermitTypeIds
) {}
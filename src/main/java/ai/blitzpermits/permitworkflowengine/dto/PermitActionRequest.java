package ai.blitzpermits.permitworkflowengine.dto;

import java.util.UUID;

public record PermitActionRequest(
        UUID actorId,
        String comments
) {}
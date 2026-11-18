package ai.blitzpermits.permitworkflowengine.dto;

import ai.blitzpermits.permitworkflowengine.entity.PermitStatusHistory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PermitHistoryResponse(
        UUID id,
        String fromStatus,
        String toStatus,
        String policyApplied,
        UUID actorId,
        String comments,
        OffsetDateTime createdAt
) {
    public static PermitHistoryResponse from(PermitStatusHistory history) {
        return new PermitHistoryResponse(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getPolicyApplied(),
                history.getActorId(),
                history.getComments(),
                history.getCreatedAt()
        );
    }
}
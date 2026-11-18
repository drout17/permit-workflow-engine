package ai.blitzpermits.permitworkflowengine.dto;

import ai.blitzpermits.permitworkflowengine.entity.Permit;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PermitResponse(
        UUID id,
        UUID parentPermitId,
        String permitTypeCode,
        String permitTypeName,
        String status,
        Integer resubmissionCycle,
        OffsetDateTime updatedAt
) {
    public static PermitResponse from(Permit permit) {
        return new PermitResponse(
                permit.getId(),
                permit.getParentPermit() != null ? permit.getParentPermit().getId() : null,
                permit.getPermitType().getCode(),
                permit.getPermitType().getName(),
                permit.getStatus(),
                permit.getResubmissionCycle(),
                permit.getUpdatedAt()
        );
    }
}
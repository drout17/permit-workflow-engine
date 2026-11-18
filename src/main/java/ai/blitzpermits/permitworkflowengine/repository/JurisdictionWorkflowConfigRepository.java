package ai.blitzpermits.permitworkflowengine.repository;

import ai.blitzpermits.permitworkflowengine.entity.JurisdictionWorkflowConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JurisdictionWorkflowConfigRepository extends JpaRepository<JurisdictionWorkflowConfig, UUID> {
    Optional<JurisdictionWorkflowConfig> findByJurisdictionIdAndIsCurrentTrue(UUID jurisdictionId);
    List<JurisdictionWorkflowConfig> findByJurisdictionIdOrderByVersionNumberDesc(UUID jurisdictionId);
}
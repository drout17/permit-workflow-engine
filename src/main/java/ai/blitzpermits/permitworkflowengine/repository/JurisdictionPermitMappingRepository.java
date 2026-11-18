package ai.blitzpermits.permitworkflowengine.repository;

import ai.blitzpermits.permitworkflowengine.entity.JurisdictionPermitMapping;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JurisdictionPermitMappingRepository extends JpaRepository<JurisdictionPermitMapping, UUID> {
    @EntityGraph(attributePaths = "permitType")
    List<JurisdictionPermitMapping> findByConfigId(UUID configId);
    Optional<JurisdictionPermitMapping> findByConfigIdAndPermitTypeId(UUID configId, UUID permitTypeId);
}
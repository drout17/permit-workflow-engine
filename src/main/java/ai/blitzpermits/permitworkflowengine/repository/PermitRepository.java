package ai.blitzpermits.permitworkflowengine.repository;

import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermitRepository extends JpaRepository<Permit, UUID> {
    @EntityGraph(attributePaths = {"permitType", "parentPermit"})
    List<Permit> findByProjectId(UUID projectId);
    List<Permit> findByParentPermitId(UUID parentPermitId);
    Optional<Permit> findByProjectIdAndParentPermitIsNull(UUID projectId);
}
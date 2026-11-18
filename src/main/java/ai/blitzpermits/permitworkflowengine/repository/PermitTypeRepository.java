package ai.blitzpermits.permitworkflowengine.repository;

import ai.blitzpermits.permitworkflowengine.entity.PermitType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermitTypeRepository extends JpaRepository<PermitType, UUID> {
    Optional<PermitType> findByCode(String code);
    List<PermitType> findByIsActiveTrue();
}
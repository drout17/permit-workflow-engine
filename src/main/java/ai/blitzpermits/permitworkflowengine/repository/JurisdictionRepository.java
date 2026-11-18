package ai.blitzpermits.permitworkflowengine.repository;

import ai.blitzpermits.permitworkflowengine.entity.Jurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JurisdictionRepository extends JpaRepository<Jurisdiction, UUID> {
    List<Jurisdiction> findByIsActiveTrue();
}
package ai.blitzpermits.permitworkflowengine.repository;

import ai.blitzpermits.permitworkflowengine.entity.PermitStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PermitStatusHistoryRepository extends JpaRepository<PermitStatusHistory, UUID> {
    List<PermitStatusHistory> findByPermitIdOrderByCreatedAtAsc(UUID permitId);
}
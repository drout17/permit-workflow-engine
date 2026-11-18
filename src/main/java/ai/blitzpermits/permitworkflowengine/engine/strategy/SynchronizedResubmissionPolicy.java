package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.engine.ResubmissionPolicy;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class SynchronizedResubmissionPolicy implements ResubmissionPolicy {

    private static final Set<String> RESUBMITTABLE_STATUSES = Set.of(
            "CORRECTIONS_REQUIRED",
            "ON_HOLD_FOR_SIBLINGS"
    );

    @Override
    public String getModeCode() {
        return "SYNCHRONIZED";
    }

    @Override
    public PolicyDecision evaluateResubmission(Permit targetPermit, List<Permit> allProjectPermits) {
        if (!RESUBMITTABLE_STATUSES.contains(targetPermit.getStatus())) {
            return PolicyDecision.deny(
                    "Permit cannot be resubmitted from current status: " + targetPermit.getStatus()
            );
        }
        return PolicyDecision.allow();
    }

    @Override
    public List<Permit> onCorrectionsRequested(Permit targetPermit, List<Permit> allProjectPermits) {
        return allProjectPermits.stream()
                .filter(p -> !p.getId().equals(targetPermit.getId()))
                .filter(p -> !"DRAFT".equals(p.getStatus()) && !"CORRECTIONS_REQUIRED".equals(p.getStatus()))
                .peek(p -> p.setStatus("ON_HOLD_FOR_SIBLINGS"))
                .toList();
    }
}
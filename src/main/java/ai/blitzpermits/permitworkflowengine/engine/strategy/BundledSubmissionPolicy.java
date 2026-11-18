package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.engine.SubmissionPolicy;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BundledSubmissionPolicy implements SubmissionPolicy {

    @Override
    public String getModeCode() {
        return "BUNDLED";
    }

    @Override
    public PolicyDecision evaluate(Permit targetPermit, List<Permit> allProjectPermits) {
        if (!"DRAFT".equals(targetPermit.getStatus())) {
            return PolicyDecision.deny("Permit is already submitted or in progress (status: " + targetPermit.getStatus() + ").");
        }

        boolean hasOtherDraftPermits = allProjectPermits.stream()
                .anyMatch(p -> !p.getId().equals(targetPermit.getId()) && "DRAFT".equals(p.getStatus()));

        if (hasOtherDraftPermits) {
            return PolicyDecision.deny("BUNDLED submission policy requires all project permits to be submitted together.");
        }

        return PolicyDecision.allow();
    }
}
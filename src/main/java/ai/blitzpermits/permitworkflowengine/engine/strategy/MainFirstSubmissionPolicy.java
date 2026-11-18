package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.engine.SubmissionPolicy;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MainFirstSubmissionPolicy implements SubmissionPolicy {

    @Override
    public String getModeCode() {
        return "MAIN_FIRST";
    }

    @Override
    public PolicyDecision evaluate(Permit targetPermit, List<Permit> allProjectPermits) {
        if (!"DRAFT".equals(targetPermit.getStatus())) {
            return PolicyDecision.deny("Permit is already submitted or in progress (status: " + targetPermit.getStatus() + ").");
        }

        if (targetPermit.getParentPermit() == null) {
            return PolicyDecision.allow();
        }

        Permit mainPermit = allProjectPermits.stream()
                .filter(p -> p.getParentPermit() == null)
                .findFirst()
                .orElse(null);

        if (mainPermit == null || "DRAFT".equals(mainPermit.getStatus())) {
            return PolicyDecision.deny("MAIN_FIRST policy requires the main permit to be submitted before any sub-permit.");
        }

        return PolicyDecision.allow();
    }
}
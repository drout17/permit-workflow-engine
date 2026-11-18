package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.engine.ResubmissionPolicy;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class DecoupledResubmissionPolicy implements ResubmissionPolicy {

    @Override
    public String getModeCode() {
        return "DECOUPLED";
    }

    @Override
    public PolicyDecision evaluateResubmission(Permit targetPermit, List<Permit> allProjectPermits) {
        if (!"CORRECTIONS_REQUIRED".equals(targetPermit.getStatus())) {
            return PolicyDecision.deny(
                    "Permit must be in CORRECTIONS_REQUIRED status to resubmit (current: " + targetPermit.getStatus() + ")."
            );
        }
        return PolicyDecision.allow();
    }

    @Override
    public List<Permit> onCorrectionsRequested(Permit targetPermit, List<Permit> allProjectPermits) {
        return Collections.emptyList();
    }
}
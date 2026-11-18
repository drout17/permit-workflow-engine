package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.ApprovalPolicy;
import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TopDownApprovalPolicy implements ApprovalPolicy {

    @Override
    public String getModeCode() {
        return "TOP_DOWN";
    }

    @Override
    public PolicyDecision evaluate(Permit targetPermit, List<Permit> allProjectPermits) {
        if (targetPermit.getParentPermit() == null) {
            return PolicyDecision.allow();
        }

        Permit mainPermit = allProjectPermits.stream()
                .filter(p -> p.getParentPermit() == null)
                .findFirst()
                .orElse(null);

        if (mainPermit == null || !"APPROVED".equals(mainPermit.getStatus())) {
            return PolicyDecision.deny("TOP_DOWN approval policy requires the main permit to be APPROVED before approving sub-permits.");
        }

        return PolicyDecision.allow();
    }
}
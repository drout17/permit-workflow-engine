package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.ApprovalPolicy;
import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BottomUpApprovalPolicy implements ApprovalPolicy {

    @Override
    public String getModeCode() {
        return "BOTTOM_UP";
    }

    @Override
    public PolicyDecision evaluate(Permit targetPermit, List<Permit> allProjectPermits) {
        if (targetPermit.getParentPermit() != null) {
            return PolicyDecision.allow();
        }

        boolean unapprovedSubPermitsExist = allProjectPermits.stream()
                .filter(p -> p.getParentPermit() != null)
                .anyMatch(p -> !"APPROVED".equals(p.getStatus()));

        if (unapprovedSubPermitsExist) {
            return PolicyDecision.deny("BOTTOM_UP approval policy requires all sub-permits to be APPROVED before approving the main permit.");
        }

        return PolicyDecision.allow();
    }
}
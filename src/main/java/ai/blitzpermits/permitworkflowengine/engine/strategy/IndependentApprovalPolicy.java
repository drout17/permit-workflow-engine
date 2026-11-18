package ai.blitzpermits.permitworkflowengine.engine.strategy;

import ai.blitzpermits.permitworkflowengine.engine.ApprovalPolicy;
import ai.blitzpermits.permitworkflowengine.engine.PolicyDecision;
import ai.blitzpermits.permitworkflowengine.entity.Permit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndependentApprovalPolicy implements ApprovalPolicy {

    @Override
    public String getModeCode() {
        return "INDEPENDENT";
    }

    @Override
    public PolicyDecision evaluate(Permit targetPermit, List<Permit> allProjectPermits) {
        return PolicyDecision.allow();
    }
}
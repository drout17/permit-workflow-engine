package ai.blitzpermits.permitworkflowengine.engine;

import ai.blitzpermits.permitworkflowengine.entity.Permit;

import java.util.List;

public interface SubmissionPolicy {
    String getModeCode();
    PolicyDecision evaluate(Permit targetPermit, List<Permit> allProjectPermits);
}
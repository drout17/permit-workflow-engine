package ai.blitzpermits.permitworkflowengine.engine;

import ai.blitzpermits.permitworkflowengine.entity.Permit;

import java.util.List;

public interface ResubmissionPolicy {
    String getModeCode();
    PolicyDecision evaluateResubmission(Permit targetPermit, List<Permit> allProjectPermits);
    List<Permit> onCorrectionsRequested(Permit targetPermit, List<Permit> allProjectPermits);
}
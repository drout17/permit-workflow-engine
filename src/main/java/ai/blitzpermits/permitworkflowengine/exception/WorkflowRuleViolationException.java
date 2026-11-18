package ai.blitzpermits.permitworkflowengine.exception;

public class WorkflowRuleViolationException extends RuntimeException {
    public WorkflowRuleViolationException(String message) {
        super(message);
    }
}
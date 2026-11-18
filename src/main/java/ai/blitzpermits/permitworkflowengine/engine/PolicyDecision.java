package ai.blitzpermits.permitworkflowengine.engine;

public sealed interface PolicyDecision permits PolicyDecision.Allowed, PolicyDecision.Denied {

    record Allowed() implements PolicyDecision {}
    record Denied(String reason) implements PolicyDecision {}

    static PolicyDecision allow() {
        return new Allowed();
    }

    static PolicyDecision deny(String reason) {
        return new Denied(reason);
    }
}
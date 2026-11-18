package ai.blitzpermits.permitworkflowengine.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PolicyRegistry {

    private final Map<String, SubmissionPolicy> submissionPolicies;
    private final Map<String, ApprovalPolicy> approvalPolicies;
    private final Map<String, ResubmissionPolicy> resubmissionPolicies;

    public PolicyRegistry(
            List<SubmissionPolicy> submissionPolicyList,
            List<ApprovalPolicy> approvalPolicyList,
            List<ResubmissionPolicy> resubmissionPolicyList
    ) {
        this.submissionPolicies = submissionPolicyList.stream()
                .collect(Collectors.toUnmodifiableMap(SubmissionPolicy::getModeCode, Function.identity()));
        this.approvalPolicies = approvalPolicyList.stream()
                .collect(Collectors.toUnmodifiableMap(ApprovalPolicy::getModeCode, Function.identity()));
        this.resubmissionPolicies = resubmissionPolicyList.stream()
                .collect(Collectors.toUnmodifiableMap(ResubmissionPolicy::getModeCode, Function.identity()));
    }

    public SubmissionPolicy getSubmissionPolicy(String modeCode) {
        SubmissionPolicy policy = submissionPolicies.get(modeCode);
        if (policy == null) {
            throw new IllegalArgumentException("Unsupported submission mode configured: " + modeCode);
        }
        return policy;
    }

    public ApprovalPolicy getApprovalPolicy(String modeCode) {
        ApprovalPolicy policy = approvalPolicies.get(modeCode);
        if (policy == null) {
            throw new IllegalArgumentException("Unsupported approval mode configured: " + modeCode);
        }
        return policy;
    }

    public ResubmissionPolicy getResubmissionPolicy(String modeCode) {
        ResubmissionPolicy policy = resubmissionPolicies.get(modeCode);
        if (policy == null) {
            throw new IllegalArgumentException("Unsupported resubmission mode configured: " + modeCode);
        }
        return policy;
    }
}
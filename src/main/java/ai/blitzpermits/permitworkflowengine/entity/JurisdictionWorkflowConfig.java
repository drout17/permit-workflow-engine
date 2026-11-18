package ai.blitzpermits.permitworkflowengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "jurisdiction_workflow_configs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_jurisdiction_config_version",
                columnNames = {"jurisdiction_id", "version_number"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JurisdictionWorkflowConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jurisdiction_id", nullable = false)
    private Jurisdiction jurisdiction;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private Boolean isCurrent = false;

    @Column(name = "submission_mode", nullable = false, length = 30)
    private String submissionMode;

    @Column(name = "approval_mode", nullable = false, length = 30)
    private String approvalMode;

    @Column(name = "resubmission_mode", nullable = false, length = 30)
    private String resubmissionMode;

    @Column(name = "custom_rules_json", columnDefinition = "CLOB")
    private String customRulesJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
        if (this.isCurrent == null) {
            this.isCurrent = false;
        }
    }
}
package com.tradinglabs.vidingest.pipeline.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "vidingest_pipeline_runs")
// Without this Hibernate emits a static full-column UPDATE, so a writer that only touches
// `phase` also rewrites `status` from whatever it read at transaction start — enough to
// clobber a terminal status another thread committed in between. See RunAggregationService.
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RunStatus status;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private PipelineRunPhase phase;

    @Column(name = "phase_updated_at")
    private LocalDateTime phaseUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 80)
    private PipelineErrorCode errorCode;

    @Column(name = "video_url", columnDefinition = "TEXT")
    private String videoUrl;

    /**
     * The optional phases this run opted out of.
     *
     * <p>Stored because a retry has to reproduce the run it is retrying, and nothing else records
     * what the operator turned off: the runs board has no phase picker at all, and reconstructing
     * the set from the audit trail cannot work — a phase after the one that failed was never
     * reached, which is indistinguishable from skipped.
     */
    @Convert(converter = PhaseSetConverter.class)
    @Column(name = "skip_phases", columnDefinition = "TEXT")
    @Builder.Default
    private Set<PipelineRunPhase> skipPhases = EnumSet.noneOf(PipelineRunPhase.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = RunStatus.PENDING;
        }
        if (phase == null) {
            phase = PipelineRunPhase.CREATED;
            phaseUpdatedAt = LocalDateTime.now();
        } else if (phaseUpdatedAt == null) {
            phaseUpdatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void setPhase(PipelineRunPhase phase) {
        this.phase = phase;
        this.phaseUpdatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineRun that = (PipelineRun) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

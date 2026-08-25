package com.tradinglabs.vidingest.pipeline.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vidingest_pipeline_run_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_run_id", nullable = false)
    private PipelineRun pipelineRun;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private PipelineRunPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_phase", length = 50)
    private PipelineRunPhase failedPhase;

    @Column(name = "phase_updated_at")
    private LocalDateTime phaseUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 80)
    private PipelineErrorCode errorCode;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "video_id")
    private UUID videoId;

    @Column(nullable = false)
    private Integer attempt;

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
        if (attempt == null) {
            attempt = 1;
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
        PipelineRunItem that = (PipelineRunItem) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}


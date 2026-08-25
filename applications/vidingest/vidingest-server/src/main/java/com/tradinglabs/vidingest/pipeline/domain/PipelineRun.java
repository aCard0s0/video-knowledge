package com.tradinglabs.vidingest.pipeline.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vidingest_pipeline_runs")
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

package com.tradinglabs.vidingest.pipeline.repo;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PipelineRun entities
 * Part of the ingestion feature
 */
@Repository
public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {

    interface PipelineRunLiveSummaryRow {
        UUID getId();

        RunStatus getStatus();

        PipelineRunPhase getPhase();

        PipelineErrorCode getErrorCode();

        String getError();

        OffsetDateTime getCreatedAt();

        OffsetDateTime getUpdatedAt();
    }

    /**
     * {@code SELECT ... FOR UPDATE} on one run row. Used only by
     * {@link com.tradinglabs.vidingest.pipeline.service.RunAggregationService#refreshRunState}
     * — which reads the run's items and then writes the run, a read-then-write across two
     * tables that READ COMMITTED alone does not make safe.
     *
     * <p>Deliberately <b>not</b> used by the per-phase writers: every audit-event insert
     * already takes {@code FOR KEY SHARE} on this row via its FK, which a plain {@code UPDATE}
     * ({@code FOR NO KEY UPDATE}) tolerates and {@code FOR UPDATE} does not. Putting this on
     * the hot path would serialise phase transitions against their own audit trail.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PipelineRun> findWithLockById(UUID id);

    List<PipelineRun> findByStatusOrderByCreatedAtDesc(RunStatus status);

    Page<PipelineRun> findByStatus(RunStatus status, Pageable pageable);

    @Query("""
            select
              r.id as id,
              r.status as status,
              r.phase as phase,
              r.errorCode as errorCode,
              r.error as error,
              r.createdAt as createdAt,
              r.updatedAt as updatedAt
            from PipelineRun r
            where r.id in :ids
            """)
    List<PipelineRunLiveSummaryRow> findLiveSummaryByIdIn(@Param("ids") Collection<UUID> ids);
}

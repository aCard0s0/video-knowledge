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

    /**
     * The listing behind {@code GET /pipelines}: a status and a lower bound on {@code createdAt},
     * either of which may be absent.
     *
     * One query rather than the four that two nullable filters would need as derived methods.
     * {@code createdAfter} exists because the console's "today" and "this week" ranges were a
     * client-side cut of whatever one page happened to hold — correct only while every run fits in
     * a page, and silently a *window* rather than a range past that. It takes an instant, not a
     * named range: the server cannot know which midnight the caller means, and the caller does.
     *
     * <p>{@code coalesce}, not {@code :createdAfter is null}: a parameter whose only appearance is
     * inside {@code is null} reaches Postgres with no type to infer from, and the driver answers
     * <em>could not determine data type of parameter $3</em> — for <b>every</b> listing, filtered
     * or not, since this one query now serves them all. Coalescing against the column itself gives
     * the bind a type, and an absent bound becomes {@code created_at >= created_at}, which is true
     * for a NOT NULL column. {@code :status} needs no such help: Hibernate types that bind from the
     * enum comparison beside it.
     */
    @Query("""
            select r from PipelineRun r
            where (:status is null or r.status = :status)
              and r.createdAt >= coalesce(:createdAfter, r.createdAt)
            """)
    Page<PipelineRun> findPage(
            @Param("status") RunStatus status,
            @Param("createdAfter") OffsetDateTime createdAfter,
            Pageable pageable);

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

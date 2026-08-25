package com.tradinglabs.vidingest.pipeline.repo;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();
    }

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

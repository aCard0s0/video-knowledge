package com.tradinglabs.vidingest.pipeline.repo;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineRunItemRepository extends JpaRepository<PipelineRunItem, UUID> {

    List<PipelineRunItem> findByPipelineRun_Id(UUID pipelineRunId, Sort sort);

    Optional<PipelineRunItem> findByIdAndPipelineRun_Id(UUID itemId, UUID pipelineRunId);

    default List<PipelineRunItem> findByPipelineRunIdOrdered(UUID pipelineRunId) {
        return findByPipelineRun_Id(pipelineRunId, Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    long countByStatusIn(Collection<RunStatus> statuses);

    /** Total run-items in a run — used for run-completion summary logging. */
    long countByPipelineRun_Id(UUID pipelineRunId);

    /** Run-items in a run with a given terminal status — used for run-completion summary logging. */
    long countByPipelineRun_IdAndStatus(UUID pipelineRunId, RunStatus status);

    List<PipelineRunItem> findByStatusAndPhaseUpdatedAtBefore(RunStatus status, java.time.LocalDateTime before);
}


package com.tradinglabs.vidingest.pipeline.repo;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PipelineRunItemEventRepository
        extends JpaRepository<PipelineRunItemEvent, UUID>, JpaSpecificationExecutor<PipelineRunItemEvent> {

    Page<PipelineRunItemEvent> findByRunItemIdOrderByOccurredAtAscIdAsc(UUID runItemId, Pageable pageable);

    Page<PipelineRunItemEvent> findByPipelineRunIdOrderByOccurredAtAscIdAsc(UUID pipelineRunId, Pageable pageable);
}

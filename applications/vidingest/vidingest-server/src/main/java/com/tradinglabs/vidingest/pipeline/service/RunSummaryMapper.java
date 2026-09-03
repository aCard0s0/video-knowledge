package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.videos.repo.RunVideoPreview;
import org.springframework.stereotype.Component;

@Component
public class RunSummaryMapper {

    /**
     * Builds the run-list {@link RunSummary} from a lightweight {@link RunVideoPreview} preview row
     * instead of a full {@code Video} entity, so the list view never hydrates JSONB metadata (#266).
     *
     * <p>Ids, instants and nullable text pass straight through — the record types them. What is
     * left is the enum-to-name conversion, which stays until those enums live in
     * {@code vidingest-api}.
     */
    public RunSummary toSummary(PipelineRun run, RunVideoPreview preview, int videoCount) {
        return new RunSummary(
                run.getId(),
                run.getStatus() != null ? run.getStatus().name() : null,
                run.getPhase() != null ? run.getPhase().name() : null,
                run.getErrorCode() != null ? run.getErrorCode().name() : null,
                run.getError(),
                run.getVideoUrl(),
                preview != null ? preview.videoId() : null,
                preview != null ? preview.channelName() : null,
                preview != null ? preview.title() : null,
                videoCount,
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }
}

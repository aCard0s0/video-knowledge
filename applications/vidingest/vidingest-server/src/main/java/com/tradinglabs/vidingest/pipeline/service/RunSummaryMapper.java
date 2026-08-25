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
     */
    public RunSummary toSummary(PipelineRun run, RunVideoPreview preview, int videoCount) {
        return new RunSummary(
                run.getId() != null ? run.getId().toString() : "",
                run.getStatus() != null ? run.getStatus().name() : "",
                run.getPhase() != null ? run.getPhase().name() : "",
                run.getErrorCode() != null ? run.getErrorCode().name() : "",
                safe(run.getError()),
                safe(run.getVideoUrl()),
                preview != null && preview.videoId() != null ? preview.videoId().toString() : "",
                preview != null ? safe(preview.channelName()) : "",
                preview != null ? safe(preview.title()) : "",
                videoCount,
                run.getCreatedAt() != null ? run.getCreatedAt().toString() : "",
                run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : ""
        );
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}


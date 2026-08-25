package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.api.pipeline.RunDetails;
import com.tradinglabs.vidingest.videos.repo.RunVideoPreview;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the run-details read model.
 *
 * <p>Reads videos through the {@link RunVideoPreview} projection rather than the {@code Video}
 * entity: the six fields this view shows are the six the projection carries, and hydrating
 * entities dragged the JSONB {@code metadata} column along for nothing — the same cost the
 * run-list path shed in #266. Preview selection is {@link RunVideoPreview#PREVIEW_ORDER},
 * shared with that path instead of reimplemented here.
 */
@Component
@RequiredArgsConstructor
public class RunDetailsMapper {

    private final VideoRepository videoRepository;
    private final PipelineRunItemRepository pipelineRunItemRepository;

    public RunDetails toDetails(PipelineRun run) {
        UUID runId = run.getId();
        List<PipelineRunItem> items = (runId != null)
                ? pipelineRunItemRepository.findByPipelineRunIdOrdered(runId)
                : List.of();

        List<RunVideoPreview> videos = (runId != null)
                ? videoRepository.findRunVideoPreviews(List.of(runId)).stream()
                        .filter(v -> v != null && v.videoId() != null)
                        .toList()
                : List.of();

        Map<UUID, RunVideoPreview> videoById = new HashMap<>();
        for (RunVideoPreview v : videos) {
            videoById.put(v.videoId(), v);
        }

        RunVideoPreview previewVideo = videos.stream().min(RunVideoPreview.PREVIEW_ORDER).orElse(null);
        String previewVideoId = previewVideo != null ? previewVideo.videoId().toString() : "";
        String previewChannelName = previewVideo != null ? safe(previewVideo.channelName()) : "";
        String previewVideoTitle = previewVideo != null ? safe(previewVideo.title()) : "";

        int videoCount = videos.size();

        List<RunDetails.RunItem> itemDtos;
        if (!items.isEmpty()) {
            itemDtos = items.stream()
                    .map(item -> {
                        RunVideoPreview itemVideo = item.getVideoId() != null ? videoById.get(item.getVideoId()) : null;
                        return new RunDetails.RunItem(
                                item.getId() != null ? item.getId().toString() : "",
                                safe(item.getUrl()),
                                item.getStatus() != null ? item.getStatus().name() : "",
                                item.getPhase() != null ? item.getPhase().name() : "",
                                item.getFailedPhase() != null ? item.getFailedPhase().name() : "",
                                item.getPhaseUpdatedAt() != null ? item.getPhaseUpdatedAt().toString() : "",
                                item.getErrorCode() != null ? item.getErrorCode().name() : "",
                                safe(item.getError()),
                                item.getVideoId() != null ? item.getVideoId().toString() : "",
                                itemVideo != null ? safe(itemVideo.channelName()) : "",
                                itemVideo != null ? safe(itemVideo.title()) : "",
                                item.getAttempt() != null ? item.getAttempt() : 1
                        );
                    })
                    .toList();
        } else {
            itemDtos = List.of(new RunDetails.RunItem(
                    "",
                    safe(run.getVideoUrl()),
                    run.getStatus() != null ? run.getStatus().name() : "",
                    run.getPhase() != null ? run.getPhase().name() : "",
                    "",
                    run.getPhaseUpdatedAt() != null ? run.getPhaseUpdatedAt().toString() : "",
                    run.getErrorCode() != null ? run.getErrorCode().name() : "",
                    safe(run.getError()),
                    previewVideoId,
                    previewChannelName,
                    previewVideoTitle,
                    1
            ));
        }

        return new RunDetails(
                run.getId() != null ? run.getId().toString() : "",
                run.getStatus() != null ? run.getStatus().name() : "",
                run.getPhase() != null ? run.getPhase().name() : "",
                run.getPhaseUpdatedAt() != null ? run.getPhaseUpdatedAt().toString() : "",
                run.getErrorCode() != null ? run.getErrorCode().name() : "",
                safe(run.getError()),
                safe(run.getVideoUrl()),
                previewVideoId,
                previewChannelName,
                previewVideoTitle,
                videoCount,
                itemDtos,
                run.getCreatedAt() != null ? run.getCreatedAt().toString() : "",
                run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : ""
        );
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}

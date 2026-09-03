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

        List<RunDetails.RunItem> itemDtos;
        if (!items.isEmpty()) {
            itemDtos = items.stream()
                    .map(item -> {
                        RunVideoPreview itemVideo = item.getVideoId() != null ? videoById.get(item.getVideoId()) : null;
                        return new RunDetails.RunItem(
                                item.getId(),
                                item.getUrl(),
                                item.getStatus() != null ? item.getStatus().name() : null,
                                item.getPhase() != null ? item.getPhase().name() : null,
                                item.getFailedPhase() != null ? item.getFailedPhase().name() : null,
                                item.getPhaseUpdatedAt(),
                                item.getErrorCode() != null ? item.getErrorCode().name() : null,
                                item.getError(),
                                item.getVideoId(),
                                itemVideo != null ? itemVideo.channelName() : null,
                                itemVideo != null ? itemVideo.title() : null,
                                item.getAttempt() != null ? item.getAttempt() : 1
                        );
                    })
                    .toList();
        } else {
            // A run with no item rows yet: describe it as a single synthetic item so the screen has
            // one lane to draw. The preview video is the run's own, since no item claims it.
            itemDtos = List.of(new RunDetails.RunItem(
                    null,
                    run.getVideoUrl(),
                    run.getStatus() != null ? run.getStatus().name() : null,
                    run.getPhase() != null ? run.getPhase().name() : null,
                    null,
                    run.getPhaseUpdatedAt(),
                    run.getErrorCode() != null ? run.getErrorCode().name() : null,
                    run.getError(),
                    previewVideo != null ? previewVideo.videoId() : null,
                    previewVideo != null ? previewVideo.channelName() : null,
                    previewVideo != null ? previewVideo.title() : null,
                    1
            ));
        }

        return new RunDetails(
                run.getId(),
                run.getStatus() != null ? run.getStatus().name() : null,
                run.getPhase() != null ? run.getPhase().name() : null,
                run.getPhaseUpdatedAt(),
                run.getErrorCode() != null ? run.getErrorCode().name() : null,
                run.getError(),
                run.getVideoUrl(),
                previewVideo != null ? previewVideo.videoId() : null,
                previewVideo != null ? previewVideo.channelName() : null,
                previewVideo != null ? previewVideo.title() : null,
                videos.size(),
                itemDtos,
                run.getCreatedAt(),
                run.getUpdatedAt(),
                // EnumSet iterates in enum order, so this arrives in pipeline order.
                run.getSkipPhases() != null
                        ? run.getSkipPhases().stream().map(Enum::name).toList()
                        : List.of()
        );
    }
}

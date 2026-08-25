package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.api.pipeline.RunDetails;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

        List<Video> videos = (runId != null)
                ? videoRepository.findAllByPipelineRun_Id(runId)
                : List.of();

        Map<UUID, Video> videoById = new HashMap<>();
        for (Video v : videos) {
            if (v == null || v.getId() == null) continue;
            videoById.put(v.getId(), v);
        }

        Video previewVideo = pickPreviewVideo(videos);
        String previewVideoId = previewVideo != null && previewVideo.getId() != null ? previewVideo.getId().toString() : "";
        String previewChannelName = previewVideo != null ? safe(previewVideo.getChannelName()) : "";
        String previewVideoTitle = previewVideo != null ? safe(previewVideo.getTitle()) : "";

        int videoCount = videos.size();

        List<RunDetails.RunItem> itemDtos;
        if (!items.isEmpty()) {
            itemDtos = items.stream()
                    .map(item -> {
                        Video itemVideo = item.getVideoId() != null ? videoById.get(item.getVideoId()) : null;
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
                                itemVideo != null ? safe(itemVideo.getChannelName()) : "",
                                itemVideo != null ? safe(itemVideo.getTitle()) : "",
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

    private static Video pickPreviewVideo(List<Video> videos) {
        if (videos == null || videos.isEmpty()) return null;
        Video best = null;
        for (Video v : videos) {
            if (v == null) continue;
            if (best == null) {
                best = v;
                continue;
            }
            if (comparePreviewCandidate(v, best) < 0) {
                best = v;
            }
        }
        return best;
    }

    private static int comparePreviewCandidate(Video candidate, Video current) {
        int statusCmp = Integer.compare(statusRank(candidate != null ? candidate.getStatus() : null),
                statusRank(current != null ? current.getStatus() : null));
        if (statusCmp != 0) return statusCmp;

        int createdAtCmp = nullSafe(candidate != null ? candidate.getCreatedAt() : null)
                .compareTo(nullSafe(current != null ? current.getCreatedAt() : null));
        if (createdAtCmp != 0) return createdAtCmp;

        UUID candId = candidate != null ? candidate.getId() : null;
        UUID currId = current != null ? current.getId() : null;
        if (candId == null && currId == null) return 0;
        if (candId == null) return 1;
        if (currId == null) return -1;
        return candId.compareTo(currId);
    }

    private static int statusRank(VideoStatus status) {
        if (status == null) return 100;
        return switch (status) {
            case COMPLETED -> 0;
            case PROCESSING -> 1;
            case TRANSCRIBING -> 2;
            case DOWNLOADED -> 3;
            case DOWNLOADING -> 4;
            case EXTRACTING -> 5;
            case PENDING -> 6;
            case FAILED -> 7;
        };
    }

    private static LocalDateTime nullSafe(LocalDateTime value) {
        return value != null ? value : LocalDateTime.MAX;
    }
}


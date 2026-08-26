package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoLifecycleService {

    private final PipelineRunRepository jobRepository;
    private final VideoRepository videoRepository;

    @Transactional
    public Video attachToPipelineRun(UUID pipelineRunId, Video video, VideoStatus status) {
        video.setPipelineRun(jobRepository.getReferenceById(pipelineRunId));
        video.setStatus(status);
        return videoRepository.save(video);
    }

    /**
     * Moves a video out of a non-terminal status once its run item is dead.
     *
     * <p>A phase that fails <em>in process</em> already does this in its own catch — see
     * {@code TranscribePhase} and {@code ContextPhase}. A process that <em>dies</em> mid-phase
     * never runs that catch: {@code StuckItemReconciler} fails the run item, and nothing else
     * ever touches the video, so it stays {@code TRANSCRIBING} or {@code PROCESSING} for good.
     * Nothing re-reads it either, which is why the row survived every later run. Routing this
     * through {@link RunItemLifecycleService#markFailed} means the reap path and the ordinary
     * failure path answer the same way, rather than only the one that happens to have a catch.
     *
     * <p>{@code COMPLETED} and {@code FAILED} are left alone: the first is a finished video that
     * a later item's failure must not rewrite, the second is already terminal. Cancellation is
     * deliberately not wired here — its common cause is {@code DUPLICATE_VIDEO}, where the
     * existing video is healthy and must keep its status.
     */
    @Transactional
    public void markFailedIfUnfinished(UUID videoId) {
        if (videoId == null) {
            return;
        }
        videoRepository.findById(videoId).ifPresent(video -> {
            VideoStatus status = video.getStatus();
            if (status == VideoStatus.COMPLETED || status == VideoStatus.FAILED) {
                return;
            }
            video.setStatus(VideoStatus.FAILED);
            videoRepository.save(video);
        });
    }
}


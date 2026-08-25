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
}


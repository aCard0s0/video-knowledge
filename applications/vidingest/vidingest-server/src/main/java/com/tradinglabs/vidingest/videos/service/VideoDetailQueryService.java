package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.api.transcription.VideoTranscriptionDetails;
import com.tradinglabs.vidingest.api.videos.VideoArtifactCounts;
import com.tradinglabs.vidingest.api.videos.VideoDetail;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.core.transcription.service.VideoTranscriptionQueryService;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoDetailQueryService {

    private final VideoQueryService videoQueryService;
    private final VideoSummaryMapper videoSummaryMapper;
    private final VideoTranscriptionQueryService videoTranscriptionQueryService;

    private final SpeakerRepository speakerRepository;
    private final OcrResultRepository ocrResultRepository;
    private final MultimodalSegmentRepository multimodalSegmentRepository;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;
    private final KnowledgeUnitRepository knowledgeUnitRepository;

    @Transactional(readOnly = true)
    public VideoDetail getVideoDetail(UUID videoId) {
        Video video = videoQueryService.getById(videoId);

        VideoSummary summary = videoSummaryMapper.toSummary(video);
        VideoTranscriptionDetails transcription = videoTranscriptionQueryService.getTranscriptionDetails(videoId);

        VideoArtifactCounts counts = new VideoArtifactCounts(
                speakerRepository.countByVideo_Id(videoId),
                ocrResultRepository.countFramesWithOcrByVideoId(videoId),
                multimodalSegmentRepository.countByVideo_Id(videoId),
                transcriptionSegmentRepository.countByTranscription_Video_Id(videoId),
                knowledgeUnitRepository.countByVideo_Id(videoId)
        );

        return new VideoDetail(summary, transcription, counts);
    }
}


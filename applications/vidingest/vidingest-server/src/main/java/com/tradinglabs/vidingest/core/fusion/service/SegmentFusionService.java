package com.tradinglabs.vidingest.core.fusion.service;

import com.tradinglabs.vidingest.config.FusionConfig;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-Java windowing pass that merges transcript segments, speaker labels, and OCR
 * detections into {@link MultimodalSegment} rows — one row per fusion window.
 *
 * <p>Windowing rules:
 * <ul>
 *   <li>Window length = {@link FusionConfig#getWindowSeconds()}.</li>
 *   <li>Consecutive windows overlap by {@link FusionConfig#getWindowOverlapSeconds()} —
 *       so a transcript sentence near a boundary appears in both windows, giving the M6
 *       LLM redundant context.</li>
 *   <li>Step = {@code window - overlap}. Sanity-checked: zero or negative step throws.</li>
 *   <li>Timeline extent comes from the latest of: transcript end, OCR-bearing frame
 *       timestamp, video {@code durationSeconds}.</li>
 *   <li>Empty windows (no transcript text, no OCR text, no speakers) are dropped so
 *       segment_index stays dense.</li>
 *   <li>Idempotent: re-running wipes prior rows for the video and re-derives from scratch.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SegmentFusionService {

    private final FusionConfig fusionConfig;
    private final TranscriptionRepository transcriptionRepository;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;
    private final VideoFrameRepository videoFrameRepository;
    private final OcrResultRepository ocrResultRepository;
    private final SpeakerRepository speakerRepository;
    private final MultimodalSegmentRepository multimodalSegmentRepository;
    private final TransactionOperations transactionOperations;

    /**
     * Fuse-and-persist for one video. Returns the persisted segments in {@code segmentIndex}
     * order. No-op (empty list) when there is nothing to fuse — never throws on missing
     * upstream data so the phase can run unconditionally in pipelines where some signals
     * are intentionally absent.
     */
    public List<MultimodalSegment> fuse(Video video) {
        if (video == null) {
            throw new FusionFailureException("Video is null");
        }
        if (video.getId() == null) {
            throw new FusionFailureException("Video ID is null");
        }

        double window = fusionConfig.getWindowSeconds();
        double overlap = fusionConfig.getWindowOverlapSeconds();
        if (window <= 0) {
            throw new FusionFailureException("vidingest.fusion.window-seconds must be > 0, got " + window);
        }
        if (overlap < 0 || overlap >= window) {
            throw new FusionFailureException(
                    "vidingest.fusion.window-overlap-seconds must be in [0, window); got overlap=" + overlap
                            + ", window=" + window);
        }
        double step = window - overlap;

        // Pull the upstream inputs. Any of these can be empty — the fusion algorithm
        // tolerates missing signals gracefully.
        Transcription transcription = transcriptionRepository.findByVideoId(video.getId()).orElse(null);
        List<TranscriptionSegment> transSegs = transcription == null
                ? List.of()
                : transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcription.getId());
        List<VideoFrame> frames = videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId());
        List<OcrResult> ocrResults = ocrResultRepository.findByVideoIdOrderByFrameTimestamp(video.getId());
        // speaker_id on a transcript segment is a bare uuid column, so the label has to be
        // resolved here. A segment pointing at a speaker that no longer exists contributes
        // nothing, which is the honest answer rather than a stale id.
        Map<UUID, String> labelBySpeakerId = speakerRepository.findByVideo_Id(video.getId()).stream()
                .collect(HashMap::new, (m, s) -> m.put(s.getId(), s.getLabel()), HashMap::putAll);

        double maxEnd = computeMaxEnd(video, transSegs, frames, ocrResults);
        if (maxEnd <= 0.0) {
            log.warn("Fusion no-op: no signals for videoId={} (transcript={}, frames={}, ocr={})",
                    video.getId(), transSegs.size(), frames.size(), ocrResults.size());
            return persistAndReturn(video, List.of());
        }

        // Build a frame_id -> [OcrResult] lookup so we can scan OCR per window in O(1).
        Map<UUID, List<OcrResult>> ocrByFrameId = groupOcrByFrame(ocrResults);

        log.info("Fusion start: videoId={}, transcriptSegments={}, frames={}, ocrRows={}, maxEnd={}s, window={}s, overlap={}s",
                video.getId(), transSegs.size(), frames.size(), ocrResults.size(), maxEnd, window, overlap);

        List<MultimodalSegment> segments = new ArrayList<>();
        int cap = fusionConfig.getMaxSegmentsPerVideo();
        int densityIndex = 0;

        for (double start = 0.0; start < maxEnd; start += step) {
            if (cap > 0 && densityIndex >= cap) {
                log.warn("Fusion cap reached: videoId={}, segments={}, cap={}",
                        video.getId(), densityIndex, cap);
                break;
            }
            double end = Math.min(start + window, maxEnd);
            if (end - start < fusionConfig.getMinWindowSeconds()) {
                // Skip negligibly short tail windows
                break;
            }

            WindowAggregation agg = aggregate(start, end, transSegs, frames, ocrByFrameId, labelBySpeakerId);
            if (agg.isEmpty()) {
                continue;
            }

            segments.add(MultimodalSegment.builder()
                    .video(video)
                    .segmentIndex(densityIndex++)
                    .startSeconds(start)
                    .endSeconds(end)
                    .transcriptText(blankToNull(agg.transcriptText))
                    .ocrText(blankToNull(agg.ocrText))
                    .speakerLabels(agg.speakerLabels.isEmpty() ? null : agg.speakerLabels.toArray(String[]::new))
                    .build());
        }

        log.info("Fusion produced {} segments for videoId={}", segments.size(), video.getId());
        return persistAndReturn(video, segments);
    }

    /**
     * Wipe-then-repopulate in one transaction, so a re-run can never leave the video with its
     * old segments deleted and its new ones unwritten. All the slow work is already done by
     * the time we get here — fusion is pure Java — so the connection is held only for the two
     * statements.
     */
    private List<MultimodalSegment> persistAndReturn(Video video, List<MultimodalSegment> segments) {
        return transactionOperations.execute(status -> {
            // Bulk delete keeps the wipe cheap.
            multimodalSegmentRepository.deleteByVideo_Id(video.getId());
            multimodalSegmentRepository.flush();
            if (segments.isEmpty()) {
                return List.of();
            }
            return multimodalSegmentRepository.saveAll(segments);
        });
    }

    /**
     * Maximum end timestamp across all signals plus the persisted video duration. Used as
     * the upper bound of the fusion timeline. Taking the {@code max} of every available
     * source — not just falling back to duration when others are empty — ensures we cover
     * any sliver of content past the last sampled frame (e.g. a video whose final 5s have
     * no on-screen text but have spoken content that wasn't transcribed yet). Empty
     * trailing windows get skipped by the aggregation pass so the cost is zero.
     */
    static double computeMaxEnd(Video video, List<TranscriptionSegment> transSegs,
                                List<VideoFrame> frames, List<OcrResult> ocrResults) {
        double max = 0.0;
        for (TranscriptionSegment s : transSegs) {
            if (s.getEndSeconds() != null) {
                max = Math.max(max, s.getEndSeconds());
            }
        }
        for (VideoFrame f : frames) {
            if (f.getTimestampSeconds() != null) {
                max = Math.max(max, f.getTimestampSeconds());
            }
        }
        for (OcrResult r : ocrResults) {
            VideoFrame f = r.getFrame();
            if (f != null && f.getTimestampSeconds() != null) {
                max = Math.max(max, f.getTimestampSeconds());
            }
        }
        if (video != null && video.getDurationSeconds() != null) {
            max = Math.max(max, video.getDurationSeconds());
        }
        return max;
    }

    private static Map<UUID, List<OcrResult>> groupOcrByFrame(List<OcrResult> ocrResults) {
        Map<UUID, List<OcrResult>> map = new HashMap<>();
        for (OcrResult r : ocrResults) {
            VideoFrame f = r.getFrame();
            if (f == null || f.getId() == null) continue;
            map.computeIfAbsent(f.getId(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    /**
     * Pulls all signals contributing to one window into a single aggregate. Visible for
     * testing so we can pin down the per-window contract independently of the persistence
     * loop.
     */
    static WindowAggregation aggregate(
            double start,
            double end,
            List<TranscriptionSegment> transSegs,
            List<VideoFrame> frames,
            Map<UUID, List<OcrResult>> ocrByFrameId,
            Map<UUID, String> labelBySpeakerId
    ) {
        StringBuilder transcript = new StringBuilder();
        Set<String> speakers = new LinkedHashSet<>();

        // Transcript: any segment whose [start,end] overlaps the window contributes its text
        // and (optional) speaker. We rely on the upstream sort (asc by startSeconds) so the
        // concatenation order matches the speaker's spoken order.
        for (TranscriptionSegment s : transSegs) {
            if (s.getStartSeconds() == null || s.getEndSeconds() == null) continue;
            if (!overlaps(s.getStartSeconds(), s.getEndSeconds(), start, end)) continue;
            String text = s.getText();
            if (text != null && !text.isBlank()) {
                if (!transcript.isEmpty()) transcript.append(' ');
                transcript.append(text.trim());
            }
            String label = s.getSpeakerId() == null ? null : labelBySpeakerId.get(s.getSpeakerId());
            if (label != null) {
                speakers.add(label);
            }
        }

        // OCR: dedupe by trimmed text within the window (subtitle that holds for several
        // frames otherwise produces N copies of the same line). Preserve first-seen order.
        Set<String> ocrLines = new LinkedHashSet<>();
        for (VideoFrame frame : frames) {
            Double ts = frame.getTimestampSeconds();
            if (ts == null || ts < start || ts >= end) continue;
            List<OcrResult> hits = ocrByFrameId.get(frame.getId());
            if (hits == null) continue;
            for (OcrResult r : hits) {
                if (r.getText() == null) continue;
                String trimmed = r.getText().trim();
                if (!trimmed.isEmpty()) {
                    ocrLines.add(trimmed);
                }
            }
        }
        String ocrText = String.join(" ", ocrLines);

        return new WindowAggregation(transcript.toString(), ocrText, speakers);
    }

    private static boolean overlaps(double aStart, double aEnd, double bStart, double bEnd) {
        // Half-open intervals: a touches b if a.end > b.start AND a.start < b.end.
        return aEnd > bStart && aStart < bEnd;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Aggregated content for one window. Package-private so tests can construct it. */
    record WindowAggregation(String transcriptText, String ocrText, Set<String> speakerLabels) {
        boolean isEmpty() {
            return (transcriptText == null || transcriptText.isBlank())
                    && (ocrText == null || ocrText.isBlank())
                    && (speakerLabels == null || speakerLabels.isEmpty());
        }

        /** Convenience for tests; sorted so equality assertions don't depend on iteration order. */
        List<String> sortedSpeakerLabels() {
            if (speakerLabels == null) return List.of();
            return speakerLabels.stream()
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        }
    }
}

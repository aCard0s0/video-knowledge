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
import java.util.stream.Collectors;
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

        // Two passes, because the OCR chrome filter is a property of the whole video: a line can
        // only be recognised as interface furniture by how many windows it appears in, which is not
        // knowable while the first window is being built.
        List<WindowAggregation> aggregations = new ArrayList<>();
        List<double[]> bounds = new ArrayList<>();
        int cap = fusionConfig.getMaxSegmentsPerVideo();

        for (double start = 0.0; start < maxEnd; start += step) {
            if (cap > 0 && aggregations.size() >= cap) {
                log.warn("Fusion cap reached: videoId={}, segments={}, cap={}",
                        video.getId(), aggregations.size(), cap);
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
            aggregations.add(agg);
            bounds.add(new double[]{start, end});
        }

        Set<String> chrome = chromeOcrLines(aggregations,
                fusionConfig.getOcrChromeWindowRatio(), fusionConfig.getOcrChromeMinWindows());

        List<MultimodalSegment> segments = new ArrayList<>(aggregations.size());
        for (int i = 0; i < aggregations.size(); i++) {
            WindowAggregation agg = aggregations.get(i);
            segments.add(MultimodalSegment.builder()
                    .video(video)
                    .segmentIndex(i)
                    .startSeconds(bounds.get(i)[0])
                    .endSeconds(bounds.get(i)[1])
                    .transcriptText(blankToNull(agg.transcriptText))
                    .ocrText(blankToNull(agg.ocrTextExcluding(chrome)))
                    .speakerLabels(agg.speakerLabels.isEmpty() ? null : agg.speakerLabels.toArray(String[]::new))
                    .build());
        }

        if (!chrome.isEmpty()) {
            int before = aggregations.stream().mapToInt(a -> a.ocrText().length()).sum();
            int after = segments.stream()
                    .mapToInt(sg -> sg.getOcrText() == null ? 0 : sg.getOcrText().length()).sum();
            log.info("Fusion dropped {} repeated OCR line(s) as interface chrome: videoId={}, "
                            + "ocrChars {} -> {} ({}% removed)",
                    chrome.size(), video.getId(), before, after,
                    before == 0 ? 0 : (before - after) * 100 / before);
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
                if (!trimmed.isEmpty() && carriesMeaning(trimmed)) {
                    ocrLines.add(trimmed);
                }
            }
        }
        return new WindowAggregation(transcript.toString(), ocrLines, speakers);
    }

    /**
     * Whether an OCR line can carry knowledge at all, as opposed to being a chart axis.
     *
     * <p>A line with no letter in it is a number with no label, and a number with no label cannot
     * become a knowledge unit — nothing downstream can say what {@code 29,810.00} refers to. On a
     * screen-recorded trading video that is the price ladder down the side of every chart, and it
     * was <b>27% of the raw OCR characters</b> on the 3-minute short this was measured against.
     *
     * <p><b>Dropping it does not improve extraction.</b> This filter and {@link #chromeOcrLines}
     * together cut the prompt 17% and left rule recovery unchanged within the eval's noise floor
     * (13.8 against 14.8 of 19, four interleaved reps each). They are kept for the cost, not for a
     * quality gain that was tested for and not found — see {@code FusionConfig}.
     *
     * <p>Deliberately narrow. A meaningful level reaches the model inside a labelled line
     * ({@code "BOS (29360.75"}, {@code "NQ129,679.500.68%"}) and those keep their letters, so they
     * survive. The rule is "unlabelled digits", not "contains digits".
     *
     * <p>Two wider filters were measured and rejected on this data rather than skipped:
     * <ul>
     *   <li><b>OCR confidence.</b> Garbled brand overlays median 0.840 against 0.943 for
     *       everything else, but the distributions overlap: a 0.9 threshold drops 36 chrome lines
     *       and 133 content lines. Net loss at every threshold.</li>
     *   <li><b>Fixed screen position.</b> Chrome is spatially static, so a grid cell occupied in
     *       most frames should be furniture — but on a short that cuts between chart, browser and
     *       landing page, only the watermark stays put: 11 of 51 chrome lines, for 5 content lines.
     *       A fifth of the problem for a bbox-quantising pass, so not worth the code.</li>
     * </ul>
     * What remains after this and {@link #chromeOcrLines} is unstructured noise — ad copy, website
     * navigation, clickbait overlay text — that no cheap structural rule separates from content.
     * That is what the KNOWLEDGE prompt's exclusion list is for, and why it stays.
     */
    static boolean carriesMeaning(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetter(line.charAt(i))) return true;
        }
        return false;
    }

    /**
     * OCR lines that appear in at least {@code ratio} of the windows, and are therefore interface
     * furniture rather than content.
     *
     * <p>Static and content are the two kinds of thing OCR picks up, and they separate cleanly on
     * persistence: a watermark, a browser tab title or a "Subscribe" overlay is on screen for the
     * whole video, while a price level or a break-of-structure label is on screen for one window.
     * Deduping within a window (which {@code aggregate} does) collapses N frames of the same
     * watermark to one line but still repeats it once per window — enough that OCR was 54% of the
     * KNOWLEDGE phase's input on a 3-minute short, carrying the sponsor's name 21 times.
     *
     * <p>Returns empty below {@code minWindows}: "appears in most windows" cannot distinguish
     * chrome from content until there are enough windows for "most" to mean anything, and on two
     * windows a legitimate label present in both is in 100% of them.
     *
     * <p>Visible for testing.
     */
    static Set<String> chromeOcrLines(List<WindowAggregation> aggregations, double ratio, int minWindows) {
        if (aggregations == null || aggregations.size() < Math.max(minWindows, 2) || ratio >= 1.0) {
            return Set.of();
        }
        Map<String, Integer> windowsPerLine = new HashMap<>();
        for (WindowAggregation agg : aggregations) {
            if (agg.ocrLines() == null) continue;
            // Counting windows, not frames: aggregate() already deduped within the window, so each
            // line contributes at most one here whatever its frame count was.
            for (String line : agg.ocrLines()) {
                windowsPerLine.merge(line, 1, Integer::sum);
            }
        }
        double threshold = ratio * aggregations.size();
        Set<String> chrome = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> e : windowsPerLine.entrySet()) {
            if (e.getValue() >= threshold) {
                chrome.add(e.getKey());
            }
        }
        return chrome;
    }

    private static boolean overlaps(double aStart, double aEnd, double bStart, double bEnd) {
        // Half-open intervals: a touches b if a.end > b.start AND a.start < b.end.
        return aEnd > bStart && aStart < bEnd;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Aggregated content for one window. Package-private so tests can construct it.
     *
     * <p>OCR is held as its <em>lines</em> rather than as joined text, because the chrome filter
     * decides per line and only after every window is known. {@link #ocrText()} is the joined form
     * the segment stores.
     */
    record WindowAggregation(String transcriptText, Set<String> ocrLines, Set<String> speakerLabels) {
        /** Every line, joined as the segment stores it. */
        String ocrText() {
            return ocrLines == null ? "" : String.join(" ", ocrLines);
        }

        /** Joined, minus the lines the video-wide filter classified as interface chrome. */
        String ocrTextExcluding(Set<String> drop) {
            if (ocrLines == null || ocrLines.isEmpty()) return "";
            if (drop == null || drop.isEmpty()) return ocrText();
            return ocrLines.stream().filter(l -> !drop.contains(l)).collect(Collectors.joining(" "));
        }

        boolean isEmpty() {
            return (transcriptText == null || transcriptText.isBlank())
                    && (ocrLines == null || ocrLines.isEmpty())
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

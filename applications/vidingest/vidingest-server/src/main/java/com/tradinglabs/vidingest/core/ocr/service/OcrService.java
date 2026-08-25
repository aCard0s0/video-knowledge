package com.tradinglabs.vidingest.core.ocr.service;

import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.core.ocr.client.PaddleOcrClient;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.dto.OcrLine;
import com.tradinglabs.vidingest.core.ocr.dto.OcrPageResult;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs OCR over a video's sampled frames (produced by M3's {@code FrameSamplingService}),
 * filters detections by confidence + min-lines-per-frame, and persists the survivors as
 * {@link OcrResult} rows. Idempotent — re-running deletes prior OCR for the video so we
 * converge to the latest sidecar output.
 *
 * <p>Failure semantics: a single frame failing the sidecar call is logged and skipped (we
 * don't want one bad JPG to fail an otherwise good video). If <i>every</i> frame fails
 * we propagate {@link OcrFailureException} so the run is marked FAILED rather than
 * silently completing with zero results.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrService {

    private final OcrConfig ocrConfig;
    private final PaddleOcrClient paddleOcrClient;
    private final VideoFrameRepository videoFrameRepository;
    private final OcrResultRepository ocrResultRepository;

    /**
     * OCR-and-persist for a video. Returns the count of rows persisted (one per surviving
     * line across all frames). No-op (returns 0) if the video has no frames yet.
     */
    public int ocrAllFrames(Video video) {
        if (video == null) {
            throw new OcrFailureException("Video is null");
        }
        if (video.getId() == null) {
            throw new OcrFailureException("Video ID is null");
        }

        List<VideoFrame> frames = videoFrameRepository.findByVideo_IdOrderByTimestampSecondsAsc(video.getId());
        if (frames.isEmpty()) {
            log.warn("OCR skipped: no frames for videoId={} (did FrameSamplePhase run?)", video.getId());
            return 0;
        }

        log.info("OCR start: videoId={}, frames={}", video.getId(), frames.size());

        // Wipe prior OCR rows so re-runs converge cleanly. Done before the (potentially
        // slow) sidecar loop so a crash mid-run still leaves the DB consistent: empty +
        // a failed pipeline run is preferable to stale rows interleaved with fresh ones.
        int deleted = wipePriorResults(video.getId());
        if (deleted > 0) {
            log.info("OCR wiped {} prior result rows for videoId={}", deleted, video.getId());
        }

        List<OcrResult> pending = new ArrayList<>();
        int framesWithText = 0;
        int framesSkipped = 0;
        int framesFailed = 0;
        int totalLinesKept = 0;
        int cap = ocrConfig.getMaxResultsPerVideo();

        for (VideoFrame frame : frames) {
            if (cap > 0 && pending.size() >= cap) {
                log.warn("OCR cap reached: videoId={}, rowsPersisted={}, framesProcessed={}/{}",
                        video.getId(), pending.size(), framesWithText, frames.size());
                break;
            }

            Path imagePath = framePath(frame);
            if (imagePath == null || !Files.exists(imagePath)) {
                log.warn("OCR skipping frame: file missing. frameId={}, path={}", frame.getId(), frame.getFilePath());
                framesSkipped++;
                continue;
            }

            OcrPageResult page;
            try {
                page = paddleOcrClient.ocr(imagePath);
            } catch (OcrFailureException e) {
                // Per-frame failure: log and continue. Aggregate failure check happens after the loop.
                log.warn("OCR sidecar call failed for frameId={} (videoId={}): {}",
                        frame.getId(), video.getId(), e.getMessage());
                framesFailed++;
                continue;
            }

            List<OcrLine> keptLines = filterLines(page.lines());
            if (keptLines.size() < ocrConfig.getMinLinesPerFrame()) {
                framesSkipped++;
                continue;
            }

            for (OcrLine line : keptLines) {
                if (cap > 0 && pending.size() >= cap) break;
                pending.add(OcrResult.builder()
                        .frame(frame)
                        .text(line.text())
                        .confidence(line.confidence())
                        .bbox(line.bbox())
                        .language(line.language())
                        .build());
                totalLinesKept++;
            }
            framesWithText++;
        }

        // If literally every frame failed the sidecar call, that's a hard failure — surface
        // it so the pipeline run is marked FAILED rather than silently completing empty.
        if (framesFailed > 0 && framesFailed == frames.size()) {
            throw new OcrFailureException(
                    "OCR failed for every frame (count=" + framesFailed + ") — sidecar likely unreachable or broken"
            );
        }

        persist(pending);

        log.info("OCR complete: videoId={}, framesTotal={}, framesWithText={}, framesSkipped={}, framesFailed={}, rowsPersisted={}",
                video.getId(), frames.size(), framesWithText, framesSkipped, framesFailed, totalLinesKept);
        return totalLinesKept;
    }

    @Transactional
    protected int wipePriorResults(UUID videoId) {
        int n = ocrResultRepository.deleteByVideoId(videoId);
        ocrResultRepository.flush();
        return n;
    }

    @Transactional
    protected void persist(List<OcrResult> rows) {
        if (rows.isEmpty()) {
            return;
        }
        ocrResultRepository.saveAll(rows);
    }

    private List<OcrLine> filterLines(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        double minConfidence = ocrConfig.getMinConfidence();
        List<OcrLine> out = new ArrayList<>(lines.size());
        for (OcrLine line : lines) {
            if (line == null || line.text() == null || line.text().isBlank()) {
                continue;
            }
            if (line.confidence() != null && line.confidence() < minConfidence) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static Path framePath(VideoFrame frame) {
        if (frame.getFilePath() == null || frame.getFilePath().isBlank()) {
            return null;
        }
        return Path.of(frame.getFilePath());
    }
}

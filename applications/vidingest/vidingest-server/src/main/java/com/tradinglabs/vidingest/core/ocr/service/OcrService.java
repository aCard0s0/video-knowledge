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
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs OCR over a video's sampled frames (produced by M3's {@code FrameSamplingService}),
 * filters detections by confidence + min-lines-per-frame, and persists the survivors as
 * {@link OcrResult} rows. Idempotent — re-running replaces the video's prior OCR in one
 * transaction so we converge to the latest sidecar output without a window where it has neither.
 *
 * <p>Failure semantics: a single frame failing the sidecar call, or having lost its JPG, is
 * logged and skipped (we don't want one bad frame to fail an otherwise good video). If
 * <i>no</i> frame was readable — every one either failed the sidecar or has no file left on
 * disk — we propagate {@link OcrFailureException} so the run is marked FAILED rather than
 * silently replacing the video's OCR with nothing. A frame that
 * OCR'd fine but held too little text is not a failure: a video with no on-screen text
 * legitimately produces zero rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrService {

    private final OcrConfig ocrConfig;
    private final PaddleOcrClient paddleOcrClient;
    private final VideoFrameRepository videoFrameRepository;
    private final OcrResultRepository ocrResultRepository;
    private final TransactionOperations transactionOperations;

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

        List<OcrResult> pending = new ArrayList<>();
        int framesWithText = 0;
        int framesSkipped = 0;
        int framesFailed = 0;
        // Tracked apart from framesSkipped: a frame whose JPG is gone is upstream artifact
        // loss, not "this frame had no text", and only the former should fail the phase.
        int framesMissing = 0;
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
                framesMissing++;
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

        // If not one frame was even readable — each either failed the sidecar or had no JPG left
        // on disk — then replacing the prior rows with nothing would destroy every OCR result for
        // the video and still report success. A frame that OCR'd fine but held too little text is
        // NOT a failure: a video with no on-screen text legitimately produces zero rows, which is
        // all framesSkipped means. Thrown before the replace, so the prior rows survive.
        if (framesFailed + framesMissing == frames.size()) {
            throw new OcrFailureException(
                    "OCR failed for every frame (sidecarFailures=" + framesFailed
                            + ", missingJpgs=" + framesMissing + ", frames=" + frames.size()
                            + ") — sidecar unreachable, or the frames were removed from disk"
            );
        }

        replaceAll(video.getId(), pending);

        log.info("OCR complete: videoId={}, framesTotal={}, framesWithText={}, framesSkipped={}, framesMissing={}, framesFailed={}, rowsPersisted={}",
                video.getId(), frames.size(), framesWithText, framesSkipped, framesMissing, framesFailed, totalLinesKept);
        return totalLinesKept;
    }

    /**
     * Wipe-then-repopulate in one transaction. The wipe used to commit on its own before the
     * sidecar loop, so a crash or a throw anywhere in those minutes left the video with its OCR
     * destroyed and nothing to replace it.
     *
     * <p>Only the two statements are transactional — the sidecar loop above still holds no pooled
     * connection, which is the constraint that put the wipe before the loop in the first place.
     * Which frames are allowed to fail without failing the phase is unchanged: a bad frame is
     * still skipped, and a partial result still replaces the previous one, atomically.
     */
    private void replaceAll(UUID videoId, List<OcrResult> rows) {
        transactionOperations.executeWithoutResult(status -> {
            int deleted = ocrResultRepository.deleteByVideoId(videoId);
            ocrResultRepository.flush();
            if (deleted > 0) {
                log.info("OCR replacing {} prior result rows for videoId={}", deleted, videoId);
            }
            if (!rows.isEmpty()) {
                ocrResultRepository.saveAll(rows);
            }
        });
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

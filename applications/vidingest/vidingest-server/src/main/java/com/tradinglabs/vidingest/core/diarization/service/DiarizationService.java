package com.tradinglabs.vidingest.core.diarization.service;

import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.core.diarization.client.DiarizationClient;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationResult;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSegment;
import com.tradinglabs.vidingest.core.diarization.dto.DiarizationSpeaker;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates speaker diarization for a single {@link Video}:
 *
 * <ol>
 *   <li>Re-extracts a 16kHz mono PCM WAV via {@code ffmpeg} (same shape Whisper consumes).
 *       We don't reuse the Whisper-produced WAV because that one is deleted in
 *       {@code TranscriptionService.finally{}} and we want this phase to be independently
 *       runnable (e.g. re-diarize an existing transcription in a future regenerate endpoint).</li>
 *   <li>POSTs the WAV to the {@code diarize-asr} sidecar via {@link DiarizationClient}.</li>
 *   <li>Upserts {@link Speaker} rows for every unique pyannote label.</li>
 *   <li>Assigns {@code speaker_id} on each {@code TranscriptionSegment} of the video's
 *       transcription, using the speaker whose diarization window overlaps the segment by
 *       at least {@code vidingest.diarization.min-overlap-seconds}.</li>
 * </ol>
 *
 * <p>If the video has no completed transcription, the phase is a no-op with a warning log.
 * Failures bubble up as {@link DiarizationFailureException}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiarizationService {

    private final DiarizationClient diarizationClient;
    private final DiarizationConfig diarizationConfig;
    private final SpeakerRepository speakerRepository;
    private final TranscriptionRepository transcriptionRepository;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;
    private final TransactionOperations transactionOperations;

    private Path scratchDir;

    @PostConstruct
    public void init() {
        this.scratchDir = Path.of(System.getProperty("java.io.tmpdir")).resolve("vidingest");
        log.info("Diarization scratch dir: {}", scratchDir);
    }

    /**
     * Run the full diarize-then-assign flow for the given video. Idempotent — re-running
     * deletes prior speakers for the video and resets segment.speaker_id so we converge to
     * the latest pyannote output without stale rows.
     */
    public DiarizationResult diarize(Video video) {
        if (video == null) {
            throw new DiarizationFailureException("Video is null");
        }
        if (video.getId() == null) {
            throw new DiarizationFailureException("Video ID is null");
        }
        if (video.getFilePath() == null || video.getFilePath().isBlank()) {
            throw new DiarizationFailureException("Video file path is empty for video " + video.getId());
        }

        Transcription transcription = transcriptionRepository.findByVideoId(video.getId()).orElse(null);
        if (transcription == null) {
            log.warn("Diarization skipped: no transcription for videoId={}", video.getId());
            return new DiarizationResult(List.of(), List.of());
        }

        log.info("Diarization start: videoId={}, transcriptionId={}, inputFile={}",
                video.getId(), transcription.getId(), Path.of(video.getFilePath()).getFileName());

        try {
            Files.createDirectories(scratchDir);
        } catch (IOException e) {
            throw new DiarizationFailureException("Failed to create diarization scratch directory", e);
        }

        Path inputVideo = Path.of(video.getFilePath());
        if (!Files.exists(inputVideo)) {
            throw new DiarizationFailureException("Video file does not exist: " + inputVideo);
        }

        Path audioFile;
        try {
            audioFile = Files.createTempFile(scratchDir, "vidingest-" + video.getId() + "-", ".diarize.wav");
        } catch (IOException e) {
            throw new DiarizationFailureException("Failed to create scratch audio file", e);
        }

        try {
            try {
                long extractStartNs = System.nanoTime();
                extractAudio(inputVideo, audioFile);
                long audioBytes = safeSize(audioFile);
                log.info(
                        "Audio extracted for diarization: videoId={}, wavFile={}, bytes={}, elapsedMs={}",
                        video.getId(),
                        audioFile.getFileName(),
                        audioBytes >= 0 ? audioBytes : "unknown",
                        elapsedMs(extractStartNs)
                );
            } catch (IOException e) {
                throw new DiarizationFailureException("Audio extraction failed", e);
            }

            DiarizationResult result = diarizationClient.diarize(audioFile);
            log.info("Diarization sidecar returned: videoId={}, segments={}, speakers={}",
                    video.getId(), result.segments().size(), result.speakers().size());

            transactionOperations.executeWithoutResult(
                    status -> persistAndAssign(video, transcription.getId(), result));
            return result;
        } finally {
            bestEffortDelete(audioFile);
        }
    }

    /**
     * Persists Speaker rows (replacing any prior diarization for this video) and writes
     * {@code transcription_segments.speaker_id} via time-overlap.
     *
     * <p>Callers must supply the transaction — {@link #diarize} wraps this in one. It used to
     * claim a transaction it never had (the annotation sat on a self-invoked protected
     * method, which Spring ignores), so the speaker wipe committed on its own and its
     * {@code ON DELETE SET NULL} FK cleared every {@code speaker_id}; a failure before the
     * segment write below left the video permanently unlabelled. The ambient transaction also
     * keeps the segments managed, so the write is dirty-checking rather than a merge per row.
     */
    protected void persistAndAssign(Video video, UUID transcriptionId, DiarizationResult result) {
        // Clear out previous diarization output so re-runs converge cleanly.
        // The ON DELETE SET NULL FK on transcription_segments.speaker_id ensures we don't
        // cascade-delete transcript rows when speakers go away.
        speakerRepository.deleteByVideo_Id(video.getId());
        speakerRepository.flush();

        Map<String, Speaker> byLabel = new HashMap<>();
        for (DiarizationSpeaker spk : result.speakers()) {
            Speaker entity = Speaker.builder()
                    .video(video)
                    .label(spk.label())
                    .embeddingVoiceprint(spk.embeddingVoiceprint())
                    .build();
            byLabel.put(spk.label(), speakerRepository.save(entity));
        }
        // The sidecar's `speakers` list should already include every label that appears in
        // `segments`, but be defensive: create Speaker rows for any label seen in segments
        // that wasn't declared up-front.
        for (DiarizationSegment seg : result.segments()) {
            byLabel.computeIfAbsent(seg.speakerLabel(), label -> speakerRepository.save(
                    Speaker.builder()
                            .video(video)
                            .label(label)
                            .build()
            ));
        }

        if (result.segments().isEmpty()) {
            log.info("Diarization produced no segments for videoId={}, nothing to assign", video.getId());
            return;
        }

        List<TranscriptionSegment> transcriptSegments =
                transcriptionSegmentRepository.findByTranscriptionIdOrderByStartSecondsAsc(transcriptionId);

        int assigned = 0;
        int cleared = 0;
        double minOverlap = diarizationConfig.getMinOverlapSeconds();
        for (TranscriptionSegment ts : transcriptSegments) {
            UUID newSpeakerId = pickSpeakerByOverlap(ts, result.segments(), byLabel, minOverlap);
            UUID prev = ts.getSpeakerId();
            if (newSpeakerId != null && !newSpeakerId.equals(prev)) {
                ts.setSpeakerId(newSpeakerId);
                assigned++;
            } else if (newSpeakerId == null && prev != null) {
                ts.setSpeakerId(null);
                cleared++;
            }
        }
        transcriptionSegmentRepository.saveAll(transcriptSegments);

        log.info("Diarization assignment complete: videoId={}, totalSegments={}, assigned={}, cleared={}, speakers={}",
                video.getId(), transcriptSegments.size(), assigned, cleared, byLabel.size());
    }

    /**
     * Choose the speaker whose diarization window overlaps the transcription segment the
     * most. Linear scan is fine: a typical video has <1k transcript segments and <1k
     * diarization windows. Returns null if no candidate clears {@code minOverlapSeconds}.
     */
    private static UUID pickSpeakerByOverlap(
            TranscriptionSegment ts,
            List<DiarizationSegment> diarSegs,
            Map<String, Speaker> byLabel,
            double minOverlapSeconds
    ) {
        if (ts.getStartSeconds() == null || ts.getEndSeconds() == null) {
            return null;
        }
        double tStart = ts.getStartSeconds();
        double tEnd = ts.getEndSeconds();

        String bestLabel = null;
        double bestOverlap = 0.0;
        for (DiarizationSegment ds : diarSegs) {
            double overlap = Math.min(tEnd, ds.endSeconds()) - Math.max(tStart, ds.startSeconds());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestLabel = ds.speakerLabel();
            }
        }
        if (bestLabel == null || bestOverlap < minOverlapSeconds) {
            return null;
        }
        Speaker spk = byLabel.get(bestLabel);
        return spk != null ? spk.getId() : null;
    }

    private void extractAudio(Path inputVideo, Path outputWav) throws IOException {
        List<String> cmd = List.of(
                "ffmpeg",
                "-y",
                "-loglevel", "error",
                "-i", inputVideo.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                outputWav.toString()
        );

        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        byte[] outputBytes;
        try (var is = process.getInputStream()) {
            outputBytes = is.readAllBytes();
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiarizationFailureException("Interrupted while running ffmpeg", e);
        }

        if (exitCode != 0) {
            String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
            throw new DiarizationFailureException("ffmpeg audio extraction failed (exitCode=" + exitCode + "): " + output);
        }

        if (!Files.exists(outputWav)) {
            throw new DiarizationFailureException("ffmpeg did not produce output file: " + outputWav);
        }
    }

    private static void bestEffortDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}

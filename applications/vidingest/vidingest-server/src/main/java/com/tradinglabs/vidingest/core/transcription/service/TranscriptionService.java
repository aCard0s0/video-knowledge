package com.tradinglabs.vidingest.core.transcription.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.core.download.util.FileSystemHelper;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionStatus;
import com.tradinglabs.vidingest.core.transcription.dto.WhisperAsrResult;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.core.transcription.whisper.WhisperAsrClient;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptionService {

    private final VideoStorageConfig storageConfig;
    private final WhisperAsrClient whisperAsrClient;
    private final TranscriptionRepository transcriptionRepository;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactionOperations;

    private Path scratchDir;

    @PostConstruct
    public void init() {
        this.scratchDir = Path.of(System.getProperty("java.io.tmpdir")).resolve("vidingest");
        log.info("Transcription scratch dir: {}", scratchDir);
    }

    public Transcription transcribe(Video video) {
        if (video == null) {
            throw new TranscriptionFailureException("Video is null");
        }
        if (video.getId() == null) {
            throw new TranscriptionFailureException("Video ID is null");
        }
        if (video.getFilePath() == null || video.getFilePath().isBlank()) {
            throw new TranscriptionFailureException("Video file path is empty for video " + video.getId());
        }

        log.info("Transcription start: videoId={}, inputFile={}", video.getId(), Path.of(video.getFilePath()).getFileName());

        try {
            Files.createDirectories(scratchDir);
        } catch (IOException e) {
            throw new TranscriptionFailureException("Failed to create transcription scratch directory", e);
        }

        Path inputVideo = Path.of(video.getFilePath());
        if (!Files.exists(inputVideo)) {
            throw new TranscriptionFailureException("Video file does not exist: " + inputVideo);
        }

        String fileName = inputVideo.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String baseName = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;

        Path outDir = inputVideo.getParent();
        if (outDir == null) {
            throw new TranscriptionFailureException("Video file has no parent directory: " + inputVideo);
        }
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new TranscriptionFailureException("Failed to create transcription output directory: " + outDir, e);
        }

        Path audioFile;
        try {
            audioFile = Files.createTempFile(scratchDir, "vidingest-" + video.getId() + "-", ".whisper.wav");
        } catch (IOException e) {
            throw new TranscriptionFailureException("Failed to create scratch audio file", e);
        }

        try {
            try {
                long extractStartNs = System.nanoTime();
                extractAudio(inputVideo, audioFile);
                long audioBytes = safeSize(audioFile);
                log.info(
                        "Audio extracted: videoId={}, wavFile={}, bytes={}, elapsedMs={}",
                        video.getId(),
                        audioFile.getFileName(),
                        audioBytes >= 0 ? audioBytes : "unknown",
                        elapsedMs(extractStartNs)
                );
            } catch (IOException e) {
                throw new TranscriptionFailureException("Audio extraction failed", e);
            }

            log.info("Sending audio to Whisper ASR: videoId={}, wavFile={}", video.getId(), audioFile.getFileName());

            WhisperAsrResult result = whisperAsrClient.transcribeToJson(audioFile);
            log.info(
                    "Whisper transcription complete: videoId={}, language={}, segments={}, textChars={}",
                    video.getId(),
                    result.language() != null ? result.language() : "",
                    result.segments() != null ? result.segments().size() : 0,
                    result.text() != null ? result.text().length() : 0
            );

            // One transaction over the whole replace. Split across two commits, a failure
            // after the wipe left the row with full_text=null and zero segments — an hour of
            // Whisper time destroyed with no way back but re-transcribing. ffmpeg and the
            // Whisper POST above stay outside it, as does writeTranscriptFiles below.
            Transcription transcription = transactionOperations.execute(status -> {
                Transcription t = upsertTranscription(video.getId(), video);
                persistSegmentsAndFinalize(t.getId(), result);
                return t;
            });
            try {
                writeTranscriptFiles(outDir, baseName, result);
            } catch (IOException e) {
                throw new TranscriptionFailureException("Failed to write transcript files", e);
            }
            return transcriptionRepository.findById(transcription.getId()).orElseThrow(() ->
                    new TranscriptionFailureException("Transcription disappeared after save: " + transcription.getId()));
        } finally {
            bestEffortDelete(audioFile);
        }
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
            throw new TranscriptionFailureException("Interrupted while running ffmpeg", e);
        }

        if (exitCode != 0) {
            String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
            throw new TranscriptionFailureException("ffmpeg audio extraction failed (exitCode=" + exitCode + "): " + output);
        }

        if (!Files.exists(outputWav)) {
            throw new TranscriptionFailureException("ffmpeg did not produce output file: " + outputWav);
        }
    }

    private Transcription upsertTranscription(UUID videoId, Video video) {
        Transcription transcription = transcriptionRepository.findByVideoId(videoId)
                .orElseGet(() -> Transcription.builder()
                        .video(video)
                        .provider("whisper")
                        .status(TranscriptionStatus.TRANSCRIBING)
                        .build());

        transcription.setProvider("whisper");
        transcription.setStatus(TranscriptionStatus.TRANSCRIBING);
        transcription.setLanguage(null);
        transcription.setFullText(null);

        Transcription saved = transcriptionRepository.saveAndFlush(transcription);
        transcriptionSegmentRepository.deleteByTranscriptionId(saved.getId());
        return saved;
    }

    private void persistSegmentsAndFinalize(UUID transcriptionId, WhisperAsrResult result) {
        Transcription transcription = transcriptionRepository.findById(transcriptionId).orElseThrow(() ->
                new TranscriptionFailureException("Transcription not found: " + transcriptionId));

        transcription.setLanguage(result.language());
        transcription.setFullText(result.text());
        transcription.setStatus(TranscriptionStatus.COMPLETED);

        List<TranscriptionSegment> segments = result.segments().stream()
                .map(seg -> TranscriptionSegment.builder()
                        .transcription(transcription)
                        .startSeconds(seg.startSeconds())
                        .endSeconds(seg.endSeconds())
                        .text(seg.text())
                        .build())
                .toList();

        transcriptionRepository.save(transcription);
        transcriptionSegmentRepository.saveAll(segments);
    }

    private void writeTranscriptFiles(Path outDir, String baseName, WhisperAsrResult result) throws IOException {
        Path jsonPath = outDir.resolve(baseName + ".whisper.json");
        Path txtPath = outDir.resolve(baseName + ".whisper.txt");

        JsonNode pretty = objectMapper.readTree(result.rawJson());
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(pretty);

        atomicWriteString(jsonPath, prettyJson);
        atomicWriteString(txtPath, result.text() != null ? result.text() : "");
    }

    private static void atomicWriteString(Path target, String content) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("Target has no parent directory: " + target);
        }
        Files.createDirectories(dir);

        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            bestEffortDelete(tmp);
        }
    }

    private static void bestEffortDelete(Path path) {
        if (path == null) {
            return;
        }
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


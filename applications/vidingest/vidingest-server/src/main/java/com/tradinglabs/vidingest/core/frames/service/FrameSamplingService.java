package com.tradinglabs.vidingest.core.frames.service;

import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Extracts keyframes from a {@link Video} using a single local ffmpeg pass, persists one
 * {@code VideoFrame} row per kept JPG, and tags each row with a {@link SamplingReason}
 * classified by proximity to a fixed-interval boundary.
 *
 * <p>Idempotent: re-running for the same video wipes the {@code frames/} sibling dir and
 * the existing {@code vidingest_video_frames} rows before re-sampling.
 *
 * <p>Disk layout (frames cascade with the video on delete — see {@code VideoDeleteService}):
 * <pre>
 *   {videoPath}/{channelName}/{baseName}/{baseName}.mp4
 *   {videoPath}/{channelName}/{baseName}/frames/0001.jpg
 *   {videoPath}/{channelName}/{baseName}/frames/0002.jpg
 *   ...
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FrameSamplingService {

    private final FrameSamplingConfig frameSamplingConfig;
    private final VideoFrameRepository videoFrameRepository;

    /**
     * Full sample-and-persist flow for {@code video}. Returns the persisted frames in
     * timestamp order. No-op (returns empty list) if the video has no file path or the
     * file is missing — callers receive that as a clean signal rather than an exception.
     */
    public List<VideoFrame> sampleFrames(Video video) {
        if (video == null) {
            throw new FrameSamplingFailureException("Video is null");
        }
        if (video.getId() == null) {
            throw new FrameSamplingFailureException("Video ID is null");
        }
        if (video.getFilePath() == null || video.getFilePath().isBlank()) {
            log.warn("FrameSampling skipped: no filePath for videoId={}", video.getId());
            return List.of();
        }

        Path inputVideo = Path.of(video.getFilePath());
        if (!Files.exists(inputVideo)) {
            throw new FrameSamplingFailureException("Video file does not exist: " + inputVideo);
        }

        Path framesDir = framesDirFor(inputVideo);
        prepareFramesDir(framesDir);

        log.info("Frame sampling start: videoId={}, inputFile={}, framesDir={}",
                video.getId(), inputVideo.getFileName(), framesDir);

        long startNs = System.nanoTime();
        String stderr = runFfmpeg(inputVideo, framesDir);
        log.info("ffmpeg finished: videoId={}, elapsedMs={}", video.getId(), elapsedMs(startNs));

        List<ShowinfoParser.ParsedFrame> parsed = ShowinfoParser.parseAll(stderr);
        List<Path> jpgs = listJpgsSorted(framesDir);
        if (parsed.isEmpty() && jpgs.isEmpty()) {
            log.info("Frame sampling produced no frames: videoId={}", video.getId());
            return persistAndReturn(video, List.of());
        }

        List<VideoFrame> frames = buildFrames(video, framesDir, parsed, jpgs);
        frames = enforceMaxCap(frames, framesDir);

        log.info("Frame sampling produced {} frames for videoId={}", frames.size(), video.getId());
        return persistAndReturn(video, frames);
    }

    @Transactional
    protected List<VideoFrame> persistAndReturn(Video video, List<VideoFrame> frames) {
        // Wipe prior rows so re-runs converge; ON DELETE CASCADE on FK doesn't help us
        // here because we're not deleting the video itself.
        videoFrameRepository.deleteByVideo_Id(video.getId());
        videoFrameRepository.flush();
        if (frames.isEmpty()) {
            return List.of();
        }
        return videoFrameRepository.saveAll(frames);
    }

    /**
     * Returns the {@code frames/} subdirectory of the per-video folder. Public so
     * {@code VideoDeleteService} can compute the same path when removing a video's
     * artifacts on disk.
     */
    public Path framesDirFor(Path videoFile) {
        Path parent = videoFile.getParent();
        if (parent == null) {
            throw new FrameSamplingFailureException("Video file has no parent directory: " + videoFile);
        }
        return parent.resolve(frameSamplingConfig.getFramesDirName());
    }

    private void prepareFramesDir(Path framesDir) {
        try {
            if (Files.exists(framesDir)) {
                deleteDirContents(framesDir);
            } else {
                Files.createDirectories(framesDir);
            }
        } catch (IOException e) {
            throw new FrameSamplingFailureException("Failed to prepare frames dir: " + framesDir, e);
        }
    }

    private static void deleteDirContents(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    deleteDirContents(child);
                    Files.delete(child);
                } else {
                    Files.delete(child);
                }
            }
        }
    }

    /**
     * Visible for testing — subclass can override to inject canned stderr without invoking
     * the real binary. Returns the process's combined stderr+stdout for {@link ShowinfoParser}.
     */
    protected String runFfmpeg(Path inputVideo, Path framesDir) {
        List<String> cmd = buildFfmpegCommand(inputVideo, framesDir);
        log.debug("ffmpeg cmd: {}", String.join(" ", cmd));

        Process process;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)  // showinfo writes to stderr; merging keeps things simple
                    .start();
        } catch (IOException e) {
            throw new FrameSamplingFailureException("Failed to spawn ffmpeg: " + e.getMessage(), e);
        }

        byte[] outputBytes;
        try (var is = process.getInputStream()) {
            outputBytes = is.readAllBytes();
        } catch (IOException e) {
            throw new FrameSamplingFailureException("Failed to read ffmpeg output", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(
                    frameSamplingConfig.getFfmpegTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new FrameSamplingFailureException("Interrupted while running ffmpeg", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new FrameSamplingFailureException(
                    "ffmpeg timed out after " + frameSamplingConfig.getFfmpegTimeout());
        }

        int exitCode = process.exitValue();
        String output = new String(outputBytes, StandardCharsets.UTF_8);
        if (exitCode != 0) {
            String snippet = output.length() > 1000 ? output.substring(0, 1000) + "..." : output;
            throw new FrameSamplingFailureException("ffmpeg exited with code " + exitCode + ": " + snippet);
        }
        return output;
    }

    /**
     * Builds the ffmpeg invocation. Single pass, dual-criterion select:
     * <ul>
     *   <li>{@code gt(scene\,threshold)} — frames where the scene-detection score exceeds the threshold</li>
     *   <li>{@code isnan(prev_selected_t)+gte(t-prev_selected_t\,interval)} — first frame plus at
     *       least one frame every {@code intervalSeconds} after the previous kept frame</li>
     * </ul>
     * The {@code +} operator in ffmpeg select expressions is boolean OR. {@code showinfo}
     * appended after the select emits one informative stderr line per kept frame so we can
     * pair JPGs to timestamps.
     */
    List<String> buildFfmpegCommand(Path inputVideo, Path framesDir) {
        String selectExpr = buildSelectExpression();
        return List.of(
                "ffmpeg",
                "-y",
                "-loglevel", "info",
                "-i", inputVideo.toString(),
                "-vf", "select='" + selectExpr + "',showinfo",
                "-fps_mode", "passthrough",
                "-q:v", Integer.toString(frameSamplingConfig.getJpegQuality()),
                framesDir.resolve("%04d.jpg").toString()
        );
    }

    String buildSelectExpression() {
        // We always emit something — fall back to interval-only if scene threshold is
        // out of range, and interval-only if scene threshold is exactly zero. If both are
        // zero/disabled the phase shouldn't have run, but be defensive.
        double scene = frameSamplingConfig.getSceneChangeThreshold();
        double interval = frameSamplingConfig.getIntervalSeconds();

        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (scene > 0.0) {
            sb.append("gt(scene\\,").append(scene).append(")");
            any = true;
        }
        if (interval > 0.0) {
            if (any) sb.append("+");
            // First frame + every `interval` seconds thereafter.
            sb.append("isnan(prev_selected_t)+gte(t-prev_selected_t\\,").append(interval).append(")");
            any = true;
        }
        if (!any) {
            // Degenerate config — keep nothing rather than every frame.
            return "0";
        }
        return sb.toString();
    }

    private static List<Path> listJpgsSorted(Path framesDir) {
        try (var stream = Files.list(framesDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jpg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new FrameSamplingFailureException("Failed to list frames dir: " + framesDir, e);
        }
    }

    /**
     * Builds {@link VideoFrame} entities by pairing the on-disk JPGs (ordered by filename,
     * which mirrors ffmpeg's {@code %04d.jpg} sequence) with the showinfo records from
     * stderr (ordered by emission, which matches {@code n}). When the two lists disagree
     * in length we trust the JPG list — disk is the source of truth for downstream phases.
     */
    private List<VideoFrame> buildFrames(
            Video video,
            Path framesDir,
            List<ShowinfoParser.ParsedFrame> parsedFrames,
            List<Path> jpgs
    ) {
        if (jpgs.isEmpty()) {
            return List.of();
        }
        if (parsedFrames.size() != jpgs.size()) {
            log.warn(
                    "Frame sampling mismatch: showinfo emitted {} frames, ffmpeg wrote {} jpgs (videoId={}). " +
                            "Using JPG order; timestamps for excess JPGs will be approximated from parsed showinfo if available.",
                    parsedFrames.size(), jpgs.size(), video.getId()
            );
        }

        double interval = frameSamplingConfig.getIntervalSeconds();
        double tolerance = frameSamplingConfig.getIntervalClassificationTolerance();

        List<VideoFrame> out = new ArrayList<>(jpgs.size());
        for (int i = 0; i < jpgs.size(); i++) {
            Path jpg = jpgs.get(i);
            ShowinfoParser.ParsedFrame meta = i < parsedFrames.size() ? parsedFrames.get(i) : null;
            double timestamp = meta != null ? meta.ptsTime() : 0.0;
            SamplingReason reason = classifyReason(timestamp, interval, tolerance);
            out.add(VideoFrame.builder()
                    .video(video)
                    .frameIndex(i)
                    .timestampSeconds(timestamp)
                    .filePath(jpg.toString())
                    .samplingReason(reason)
                    .width(meta != null ? meta.width() : null)
                    .height(meta != null ? meta.height() : null)
                    .build());
        }
        return out;
    }

    /**
     * Tags a frame as {@link SamplingReason#INTERVAL} when its timestamp is within
     * {@code tolerance} of an exact {@code k * interval} boundary, otherwise
     * {@link SamplingReason#SCENE_CHANGE}. Falls back to {@code KEYFRAME} when interval
     * sampling is disabled — any frame ffmpeg kept is then necessarily a scene change or
     * the first frame.
     */
    static SamplingReason classifyReason(double timestamp, double interval, double tolerance) {
        if (interval <= 0.0) {
            return SamplingReason.KEYFRAME;
        }
        double remainder = timestamp % interval;
        double distance = Math.min(remainder, interval - remainder);
        return distance <= tolerance ? SamplingReason.INTERVAL : SamplingReason.SCENE_CHANGE;
    }

    /**
     * Trims the kept-frame list to {@code maxFramesPerVideo}, deleting the excess JPGs from
     * disk so the row count matches what's on the filesystem. Truncation strategy: keep
     * earlier frames (which include the first frame + early scene changes) and drop the
     * tail. A future improvement could subsample uniformly instead.
     */
    private List<VideoFrame> enforceMaxCap(List<VideoFrame> frames, Path framesDir) {
        int cap = frameSamplingConfig.getMaxFramesPerVideo();
        if (cap <= 0 || frames.size() <= cap) {
            return frames;
        }
        log.warn("Frame cap exceeded: {} frames -> truncating to {} (framesDir={})",
                frames.size(), cap, framesDir);
        List<VideoFrame> kept = new ArrayList<>(frames.subList(0, cap));
        for (int i = cap; i < frames.size(); i++) {
            Path jpg = Path.of(frames.get(i).getFilePath());
            try {
                Files.deleteIfExists(jpg);
            } catch (IOException ignored) {
                // best effort — extra JPG on disk is annoying but not a failure.
            }
        }
        return kept;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}

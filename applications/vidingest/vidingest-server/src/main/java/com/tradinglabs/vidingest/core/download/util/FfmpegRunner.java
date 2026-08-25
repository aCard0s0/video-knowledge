package com.tradinglabs.vidingest.core.download.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a local ffmpeg invocation under a hard timeout and returns its merged stdout+stderr.
 *
 * <p>Three services shell out to ffmpeg and all three had the same two defects. Both are the
 * reason this class exists, so neither is left to the next caller to remember:
 *
 * <ol>
 *   <li><b>The output drain must not run on the waiting thread.</b> {@code readAllBytes()} returns
 *       at EOF, and EOF on a process pipe means the process exited — so draining before
 *       {@code waitFor} makes the timeout unreachable for exactly the process it exists to kill.
 *       Frame sampling had a configured {@code PT20M} timeout that could never fire; transcription
 *       and diarization had no timeout at all. A wedged ffmpeg parked the ingestion thread
 *       forever, holding its concurrency permit, while the lease heartbeat kept renewing — so
 *       neither reaper could tell it from healthy work.</li>
 *   <li><b>ffmpeg reads stdin.</b> Without {@code -nostdin} it consumes the inherited stream, and
 *       {@code ProcessBuilder}'s default is a pipe nobody ever writes or closes — so it blocks
 *       there and never exits. {@code -nostdin} tells ffmpeg not to look; closing our end of the
 *       stdin pipe means it hits EOF rather than blocking if some build of it looks anyway.</li>
 * </ol>
 *
 * <p>stderr is merged into stdout: ffmpeg's {@code showinfo} filter writes there and
 * {@code ShowinfoParser} needs it. Merging also removes the second pipe that would otherwise need
 * its own drain to keep from filling.
 */
@Slf4j
public final class FfmpegRunner {

    /**
     * How long to wait for the drain once the process is gone. It is at EOF by then; this is a
     * backstop against a grandchild that inherited the pipe and outlived its parent.
     */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

    private FfmpegRunner() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs {@code cmd}, returning its merged stdout+stderr decoded as UTF-8.
     *
     * @param cmd     full command line; {@code -nostdin} is inserted after the binary if absent
     * @param timeout hard ceiling on the process; {@code null} or non-positive means no ceiling
     * @throws IOException on a non-zero exit, a timeout, or a failure to spawn or drain
     */
    public static String run(List<String> cmd, Duration timeout) throws IOException {
        if (cmd == null || cmd.isEmpty()) {
            throw new IllegalArgumentException("cmd must not be empty");
        }
        List<String> command = withNoStdin(cmd);
        String binary = command.getFirst();
        log.debug("ffmpeg cmd: {}", String.join(" ", command));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            // Give the child EOF on stdin. Redirect.DISCARD cannot do this — it is a write-only
            // redirect — and Redirect.INHERIT would hand a server process's own stdin to ffmpeg.
            // Closing our end of the pipe is the portable way to say "there is no input".
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new IOException("Failed to spawn " + binary + ": " + e.getMessage(), e);
        }

        // Drained on its own thread so the wait below is what bounds the run, not the pipe.
        // Virtual, not the common ForkJoinPool: this blocks for as long as ffmpeg runs, and the
        // pool has a worker per core to spare across every concurrent ingestion item.
        AtomicReference<byte[]> output = new AtomicReference<>();
        AtomicReference<IOException> drainFailure = new AtomicReference<>();
        Thread drain = Thread.ofVirtual().name("ffmpeg-drain").start(() -> {
            try (var is = process.getInputStream()) {
                output.set(is.readAllBytes());
            } catch (IOException e) {
                drainFailure.set(e);
            }
        });

        try {
            if (!waitFor(process, timeout)) {
                // destroyForcibly also closes the pipe, which is what releases the drain.
                process.destroyForcibly();
                throw new IOException(binary + " timed out after " + timeout);
            }

            drain.join(DRAIN_GRACE);
            if (drainFailure.get() != null) {
                throw new IOException("Failed to read " + binary + " output", drainFailure.get());
            }
            byte[] bytes = output.get();
            if (bytes == null) {
                throw new IOException(binary + " exited but its output was still unread after " + DRAIN_GRACE);
            }

            String text = new String(bytes, StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException(binary + " exited with code " + exitCode + ": " + snippet(text));
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while running " + binary, e);
        } finally {
            // No-op on the happy path; on every other one it is what stops us leaking a process.
            process.destroyForcibly();
        }
    }

    /** {@code true} if the process exited within the timeout. A non-positive timeout waits forever. */
    private static boolean waitFor(Process process, Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            process.waitFor();
            return true;
        }
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Inserts {@code -nostdin} directly after the binary when the caller has not passed it. Done
     * here rather than at each call site so a new caller cannot forget it — the three that existed
     * before this class all had.
     *
     * <p>Only for ffmpeg itself: the flag is meaningless to anything else, and the closed stdin
     * pipe already covers the general case. Keeping the guard means the process handling here can
     * be exercised with an ordinary shell command.
     */
    private static List<String> withNoStdin(List<String> cmd) {
        String binary = cmd.getFirst();
        if (!binary.equals("ffmpeg") && !binary.endsWith("/ffmpeg")) {
            return cmd;
        }
        if (cmd.contains("-nostdin")) {
            return cmd;
        }
        List<String> out = new ArrayList<>(cmd.size() + 1);
        out.add(cmd.getFirst());
        out.add("-nostdin");
        out.addAll(cmd.subList(1, cmd.size()));
        return out;
    }

    private static String snippet(String output) {
        String trimmed = output.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) + "..." : trimmed;
    }
}

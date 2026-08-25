package com.tradinglabs.vidingest.core.download.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Duration;

/**
 * Utility for executing yt-dlp commands
 * Handles command execution, output capture, and error handling
 */
@Slf4j
public final class YtDlpExecutor {

    private YtDlpExecutor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Execute a yt-dlp command and return stdout as string
     *
     * @param cmdLine Command to execute
     * @return stdout output as string
     * @throws IOException if execution fails
     */
    public static String executeForOutput(CommandLine cmdLine) throws IOException {
        return executeForOutput(cmdLine, 0);
    }

    /**
     * Execute a yt-dlp command and return stdout as string.
     *
     * @param cmdLine Command to execute
     * @param timeoutSeconds Watchdog timeout in seconds (0 = no timeout)
     * @return stdout output as string
     * @throws IOException if execution fails
     */
    public static String executeForOutput(CommandLine cmdLine, long timeoutSeconds) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream, errorStream);

        DefaultExecutor executor = DefaultExecutor.builder()
                .setExecuteStreamHandler(streamHandler)
                .get();
        ExecuteWatchdog watchdog = attachWatchdog(executor, timeoutSeconds);

        try {
            int exitCode = executor.execute(cmdLine);
            if (exitCode != 0) {
                String errorOutput = errorStream.toString();
                log.error("yt-dlp failed with exit code {}: {}", exitCode, errorOutput);
                throw new IOException("yt-dlp failed with exit code: " + exitCode + ". Error: " + errorOutput);
            }

            return outputStream.toString().trim();

        } catch (ExecuteException e) {
            if (isTimeout(watchdog)) {
                throw new IOException(timeoutMessage(timeoutSeconds), e);
            }
            String stdout = outputStream.toString();
            String stderr = errorStream.toString();
            log.error("ExecuteException during yt-dlp execution. Exit code: {}", e.getExitValue());
            log.error("yt-dlp stdout: {}", stdout.isEmpty() ? "(empty)" : stdout);
            log.error("yt-dlp stderr: {}", stderr.isEmpty() ? "(empty)" : stderr);
            throw new IOException(
                String.format("yt-dlp failed with exit code %d. Error: %s", 
                    e.getExitValue(), 
                    stderr.isEmpty() ? stdout : stderr), 
                e);
        }
    }

    /**
     * Execute a yt-dlp download command with working directory
     *
     * @param cmdLine Command to execute
     * @param workingDirectory Working directory for the command
     * @throws IOException if execution fails
     */
    public static void executeDownload(CommandLine cmdLine, String workingDirectory) throws IOException {
        executeDownload(cmdLine, workingDirectory, 0, false);
    }

    /**
     * Execute a yt-dlp download command with timeout support.
     *
     * @param cmdLine Command to execute
     * @param workingDirectory Working directory for the command
     * @param timeoutSeconds Watchdog timeout in seconds (0 = no timeout)
     * @throws IOException if execution fails
     */
    public static void executeDownload(CommandLine cmdLine, String workingDirectory, long timeoutSeconds) throws IOException {
        executeDownload(cmdLine, workingDirectory, timeoutSeconds, false);
    }

    /**
     * Execute a yt-dlp download command with optional progress streaming.
     *
     * @param cmdLine Command to execute
     * @param workingDirectory Working directory for the command
     * @param timeoutSeconds Watchdog timeout in seconds (0 = no timeout)
     * @param streamProgress when true, stream stdout/stderr to the active terminal
     * @throws IOException if execution fails
     */
    public static void executeDownload(
            CommandLine cmdLine,
            String workingDirectory,
            long timeoutSeconds,
            boolean streamProgress) throws IOException {
        String fullCommand = String.join(" ", cmdLine.toStrings());
        log.debug("Executing yt-dlp command: {}", fullCommand);
        log.debug("Working directory: {}", workingDirectory);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        if (streamProgress) {
            log.info("Progress streaming requested; capturing output for logs (no direct System.out/err streaming in server mode)");
        }
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream, errorStream);

        DefaultExecutor executor = DefaultExecutor.builder()
                .setExecuteStreamHandler(streamHandler)
                .setWorkingDirectory(new File(workingDirectory))
                .get();
        ExecuteWatchdog watchdog = attachWatchdog(executor, timeoutSeconds);

        try {
            log.debug("Starting video download...");
            int exitCode = executor.execute(cmdLine);

            String stdout = outputStream.toString();
            String stderr = errorStream.toString();

            logCommandOutput(stdout, stderr);

            if (exitCode != 0) {
                String errorMessage = String.format(
                    "yt-dlp failed with exit code: %d%nStdout: %s%nStderr: %s",
                    exitCode,
                    stdout.isEmpty() ? "(empty)" : stdout,
                    stderr.isEmpty() ? "(empty)" : stderr
                );
                log.error("Video download failed. Exit code: {}", exitCode);
                log.error("yt-dlp stdout: {}", stdout.isEmpty() ? "(empty)" : stdout);
                log.error("yt-dlp stderr: {}", stderr.isEmpty() ? "(empty)" : stderr);
                throw new IOException(errorMessage);
            }

        } catch (ExecuteException e) {
            if (isTimeout(watchdog)) {
                throw new IOException(timeoutMessage(timeoutSeconds), e);
            }
            String stdout = outputStream.toString();
            String stderr = errorStream.toString();
            log.error("ExecuteException during video download. Exit code: {}", e.getExitValue());
            log.error("yt-dlp stdout: {}", stdout.isEmpty() ? "(empty)" : stdout);
            log.error("yt-dlp stderr: {}", stderr.isEmpty() ? "(empty)" : stderr);
            log.error("Full command that failed: {}", fullCommand);
            throw new IOException(
                String.format("Video download failed with exit code %d. Error: %s", 
                    e.getExitValue(), 
                    stderr.isEmpty() ? stdout : stderr), 
                e);
        } catch (IOException e) {
            String stdout = outputStream.toString();
            String stderr = errorStream.toString();
            log.error("IOException during video download: {}", e.getMessage());
            if (!stdout.isEmpty()) {
                log.error("yt-dlp stdout: {}", stdout);
            }
            if (!stderr.isEmpty()) {
                log.error("yt-dlp stderr: {}", stderr);
            }
            log.error("Full command that failed: {}", fullCommand);
            throw new IOException("Video download failed: " + e.getMessage(), e);
        }
    }

    private static ExecuteWatchdog attachWatchdog(DefaultExecutor executor, long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return null;
        }
        ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
                .setTimeout(Duration.ofSeconds(timeoutSeconds))
                .get();
        executor.setWatchdog(watchdog);
        return watchdog;
    }

    private static boolean isTimeout(ExecuteWatchdog watchdog) {
        return watchdog != null && watchdog.killedProcess();
    }

    private static String timeoutMessage(long timeoutSeconds) {
        return "yt-dlp timed out after " + timeoutSeconds + " seconds";
    }

    /**
     * Log command output (stdout/stderr) with appropriate log levels
     */
    private static void logCommandOutput(String stdout, String stderr) {
        if (!stdout.isEmpty()) {
            log.debug("yt-dlp stdout (last 500 chars): {}",
                stdout.length() > 500 ? stdout.substring(stdout.length() - 500) : stdout);
        }
        if (!stderr.isEmpty()) {
            log.warn("yt-dlp stderr: {}", stderr);
        }
    }
}




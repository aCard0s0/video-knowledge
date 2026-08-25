package com.tradinglabs.vidingest.core.download.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runner is exercised with {@code sh}, not ffmpeg: the behaviour under test is process
 * handling, and the build must not need a media toolchain installed to prove it.
 */
@DisabledOnOs(OS.WINDOWS)
class FfmpegRunnerTest {

    @Test
    void returnsMergedOutputOnSuccess() throws Exception {
        String output = FfmpegRunner.run(
                List.of("sh", "-c", "echo out; echo err 1>&2"),
                Duration.ofSeconds(30)
        );

        assertThat(output).contains("out").contains("err");
    }

    @Test
    void nonZeroExitThrowsWithTheOutput() {
        assertThatThrownBy(() -> FfmpegRunner.run(
                List.of("sh", "-c", "echo boom 1>&2; exit 3"),
                Duration.ofSeconds(30)
        ))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited with code 3")
                .hasMessageContaining("boom");
    }

    /**
     * The regression this class exists for. A process that holds its stdout open past its work
     * used to park the caller in {@code readAllBytes} forever, making the timeout unreachable —
     * so the assertion that matters is that this returns at all.
     */
    @Test
    void timesOutWhileTheProcessStillHoldsItsOutputOpen() {
        long startNs = System.nanoTime();

        assertThatThrownBy(() -> FfmpegRunner.run(
                List.of("sh", "-c", "sleep 30"),
                Duration.ofMillis(500)
        ))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");

        assertThat(Duration.ofNanos(System.nanoTime() - startNs))
                .isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void aProcessThatReadsStdinDoesNotBlockForever() throws Exception {
        // Redirect.DISCARD means stdin is at EOF immediately; with ProcessBuilder's default pipe
        // this command would block until the timeout instead of returning empty.
        String output = FfmpegRunner.run(List.of("sh", "-c", "cat"), Duration.ofSeconds(10));

        assertThat(output).isEmpty();
    }
}

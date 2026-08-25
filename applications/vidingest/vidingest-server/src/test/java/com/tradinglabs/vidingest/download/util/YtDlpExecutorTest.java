package com.tradinglabs.vidingest.download.util;

import com.tradinglabs.vidingest.core.download.util.YtDlpExecutor;
import org.apache.commons.exec.CommandLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YtDlpExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void executeForOutputReturnsStdoutWhenCommandSucceeds() throws Exception {
        CommandLine cmd = new CommandLine("zsh");
        cmd.addArgument("-lc");
        cmd.addArgument("printf 'ok-output'", false);

        String output = YtDlpExecutor.executeForOutput(cmd, 5);

        assertThat(output).isEqualTo("ok-output");
    }

    @Test
    void executeForOutputThrowsTimeoutMessageWhenWatchdogKillsProcess() {
        CommandLine cmd = new CommandLine("zsh");
        cmd.addArgument("-lc");
        cmd.addArgument("sleep 3", false);

        assertThatThrownBy(() -> YtDlpExecutor.executeForOutput(cmd, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out after 1 seconds");
    }

    @Test
    void executeDownloadDoesNotTimeoutWhenWatchdogDisabled() throws Exception {
        CommandLine cmd = new CommandLine("zsh");
        cmd.addArgument("-lc");
        cmd.addArgument("sleep 1", false);

        YtDlpExecutor.executeDownload(cmd, tempDir.toString(), 0, false);
    }

    @Test
    void executeDownloadSupportsProgressStreamingWithoutBreakingExecution() throws Exception {
        CommandLine cmd = new CommandLine("zsh");
        cmd.addArgument("-lc");
        cmd.addArgument("echo '[download] 20%' 1>&2", false);

        YtDlpExecutor.executeDownload(cmd, tempDir.toString(), 5, true);
    }
}


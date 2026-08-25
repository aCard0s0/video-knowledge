package com.tradinglabs.vidingest.core.frames.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-function tests for {@link ShowinfoParser}. No ffmpeg invocation involved — these
 * lock down the regex behaviour against representative stderr samples taken from real
 * ffmpeg 6.x and 7.x runs.
 */
class ShowinfoParserTest {

    @Test
    void parsesAlignedShowinfoLine() {
        String line = "[Parsed_showinfo_1 @ 0x7f9d8e004600] n:   0 pts:      0 pts_time:0       "
                + "pos:  41 fmt:yuv420p sar:1/1 s:1920x1080 i:P iskey:1 type:I checksum:abc";

        ShowinfoParser.ParsedFrame frame = ShowinfoParser.parseLine(line);

        assertThat(frame).isNotNull();
        assertThat(frame.n()).isZero();
        assertThat(frame.ptsTime()).isCloseTo(0.0, within(1e-9));
        assertThat(frame.width()).isEqualTo(1920);
        assertThat(frame.height()).isEqualTo(1080);
    }

    @Test
    void parsesUnalignedShowinfoLine() {
        String line = "[Parsed_showinfo_1 @ 0xff] n:42 pts:1008000 pts_time:11.235 "
                + "fmt:yuv420p s:1280x720 i:P iskey:0";

        ShowinfoParser.ParsedFrame frame = ShowinfoParser.parseLine(line);

        assertThat(frame).isNotNull();
        assertThat(frame.n()).isEqualTo(42);
        assertThat(frame.ptsTime()).isCloseTo(11.235, within(1e-6));
        assertThat(frame.width()).isEqualTo(1280);
        assertThat(frame.height()).isEqualTo(720);
    }

    @Test
    void returnsNullForNonShowinfoLines() {
        assertThat(ShowinfoParser.parseLine(null)).isNull();
        assertThat(ShowinfoParser.parseLine("")).isNull();
        assertThat(ShowinfoParser.parseLine("[info] some other ffmpeg log")).isNull();
        assertThat(ShowinfoParser.parseLine("Input #0, mov,mp4,m4a, ...")).isNull();
    }

    @Test
    void parsesLineWithoutResolution() {
        String line = "[Parsed_showinfo_1 @ 0xff] n:5 pts:240000 pts_time:2.5 fmt:yuv420p";

        ShowinfoParser.ParsedFrame frame = ShowinfoParser.parseLine(line);

        assertThat(frame).isNotNull();
        assertThat(frame.n()).isEqualTo(5);
        assertThat(frame.ptsTime()).isCloseTo(2.5, within(1e-9));
        assertThat(frame.width()).isNull();
        assertThat(frame.height()).isNull();
    }

    @Test
    void parseAllReturnsFramesInEmissionOrderAndSkipsJunkLines() {
        String stderr = String.join("\n",
                "ffmpeg version 7.1 banner ...",
                "Input #0, mov,mp4,m4a, from 'video.mp4':",
                "[Parsed_showinfo_1 @ 0xff] n:0 pts:0 pts_time:0.0 s:640x360 fmt:yuv420p",
                "  duration line that mentions n:99 but is not from showinfo",
                "[Parsed_showinfo_1 @ 0xff] n:1 pts:48000 pts_time:5.0 s:640x360",
                "[Parsed_showinfo_1 @ 0xff] n:2 pts:96000 pts_time:10.0 s:640x360",
                "frame=    3 fps=0.0 q=2.0 size=N/A time=00:00:10.00 bitrate=N/A"
        );

        List<ShowinfoParser.ParsedFrame> frames = ShowinfoParser.parseAll(stderr);

        assertThat(frames).hasSize(3);
        assertThat(frames).extracting(ShowinfoParser.ParsedFrame::n).containsExactly(0, 1, 2);
        assertThat(frames).extracting(ShowinfoParser.ParsedFrame::ptsTime)
                .containsExactly(0.0, 5.0, 10.0);
        assertThat(frames).allMatch(f -> f.width() == 640 && f.height() == 360);
    }

    @Test
    void parseAllHandlesBlankInputs() {
        assertThat(ShowinfoParser.parseAll(null)).isEmpty();
        assertThat(ShowinfoParser.parseAll("")).isEmpty();
        assertThat(ShowinfoParser.parseAll("   \n\n  ")).isEmpty();
    }
}

package com.tradinglabs.vidingest.core.frames.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-function parser for the lines ffmpeg's {@code showinfo} filter emits to stderr.
 *
 * <p>A typical showinfo line looks like:
 * <pre>
 * [Parsed_showinfo_1 @ 0x7f9d8e004600] n:   0 pts:      0 pts_time:0       pos:  ...
 *   fmt:yuv420p sar:1/1 s:1920x1080 i:P iskey:1 type:I checksum:...
 * </pre>
 *
 * <p>We care about {@code n} (sequence number assigned by showinfo — matches the
 * {@code %04d.jpg} index when we use {@code -fps_mode passthrough}), {@code pts_time}
 * (the frame's timestamp in seconds), and the resolution {@code s:WxH}.
 *
 * <p>Extracted as a top-level class with a {@code static} parsing method so the regexes can
 * be unit-tested without an ffmpeg dependency.
 */
public final class ShowinfoParser {

    // ffmpeg writes "n:   0" or "n:0" depending on alignment; \\s* tolerates both.
    private static final Pattern SHOWINFO_LINE = Pattern.compile(
            "Parsed_showinfo.*?\\bn:\\s*(\\d+)\\b.*?\\bpts_time:\\s*([0-9]+(?:\\.[0-9]+)?)"
    );

    // Resolution appears later on the same logical line, but ffmpeg sometimes wraps the line
    // for long messages — keep this as a separate pattern applied to the same substring.
    private static final Pattern RESOLUTION = Pattern.compile("\\bs:(\\d+)x(\\d+)\\b");

    private ShowinfoParser() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Parses one showinfo frame line into a {@link ParsedFrame}, or returns {@code null}
     * if the line doesn't match the expected pattern. The {@code n} field is monotonically
     * increasing within an ffmpeg invocation, mirroring the {@code %04d.jpg} sequence so
     * callers can pair the resulting frames with the JPGs on disk.
     */
    public static ParsedFrame parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher m = SHOWINFO_LINE.matcher(line);
        if (!m.find()) {
            return null;
        }
        int n;
        double ptsTime;
        try {
            n = Integer.parseInt(m.group(1));
            ptsTime = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }

        Integer width = null;
        Integer height = null;
        Matcher r = RESOLUTION.matcher(line);
        if (r.find()) {
            try {
                width = Integer.parseInt(r.group(1));
                height = Integer.parseInt(r.group(2));
            } catch (NumberFormatException ignored) {
                // resolution is best-effort
            }
        }

        return new ParsedFrame(n, ptsTime, width, height);
    }

    /**
     * Parses an entire stderr blob from an ffmpeg run, returning one {@link ParsedFrame}
     * per showinfo line in stable insertion order. Lines that don't match are skipped.
     */
    public static List<ParsedFrame> parseAll(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return List.of();
        }
        List<ParsedFrame> out = new ArrayList<>();
        for (String line : stderr.split("\\r?\\n")) {
            ParsedFrame parsed = parseLine(line);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    /**
     * One showinfo record: ffmpeg's running frame index ({@code n}), the frame's
     * presentation timestamp in seconds ({@code ptsTime}), and an optional resolution.
     */
    public record ParsedFrame(int n, double ptsTime, Integer width, Integer height) {
    }
}

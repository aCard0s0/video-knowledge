package com.tradinglabs.vidingest.pipeline.util;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses the wire form of a run's phase opt-out list into {@link PipelineRunPhase} values.
 *
 * <p>The REST records carry {@code Set<String>} rather than the enum because {@code
 * vidingest-api} must not depend on the server, so validation lands here — one place, shared by
 * the create, retry and channel-driven entry points.
 *
 * <p>A name that is unknown or names a mandatory phase is an
 * {@link IllegalArgumentException}, which the API exception handler renders as a 400
 * {@code ProblemDetail}. That is deliberate: unlike a bad URL, which rejects one item of a
 * batch, an unparseable opt-out list makes the whole request meaningless.
 *
 * <p>{@link #parseOptional} is also what the per-phase rerun endpoint uses to read its
 * {@code {phase}} path variable — same normalisation, same allowed set, same message.
 */
public final class SkipPhasesParser {

    private SkipPhasesParser() {
    }

    public static Set<PipelineRunPhase> parse(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<PipelineRunPhase> parsed = EnumSet.noneOf(PipelineRunPhase.class);
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                continue;
            }
            parsed.add(parseOptional(name.trim()));
        }
        return parsed;
    }

    /** One phase name to the enum, rejecting anything unknown or mandatory. */
    public static PipelineRunPhase parseOptional(String name) {
        PipelineRunPhase phase;
        try {
            phase = PipelineRunPhase.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw unsupported(name);
        }
        if (!phase.isOptional()) {
            throw unsupported(name);
        }
        return phase;
    }

    private static IllegalArgumentException unsupported(String name) {
        return new IllegalArgumentException("Unsupported phase: " + name + ". Allowed: "
                + PipelineRunPhase.optionalPhases().stream().map(Enum::name).collect(Collectors.joining(", ")));
    }
}

package com.tradinglabs.vidingest.pipeline.util;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The six positional booleans this replaced made a mandatory phase unskippable by construction.
 * A {@code Set<String>} off the wire does not, so the guarantee has to be asserted: naming
 * METADATA/DOWNLOAD/PERSIST — or anything that is not a phase at all — is a 400, not a run that
 * starts without its own input.
 */
@Tag("unit")
class SkipPhasesParserTest {

    @Test
    void parsesOptionalPhasesCaseAndSeparatorInsensitively() {
        assertThat(SkipPhasesParser.parse(List.of("diarize", "frame-sample", "OCR", " knowledge ")))
                .containsExactlyInAnyOrder(PipelineRunPhase.DIARIZE, PipelineRunPhase.FRAME_SAMPLE,
                        PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE);
    }

    @Test
    void treatsNullEmptyAndBlankEntriesAsNothingToSkip() {
        assertThat(SkipPhasesParser.parse(null)).isEmpty();
        assertThat(SkipPhasesParser.parse(List.of())).isEmpty();
        assertThat(SkipPhasesParser.parse(Arrays.asList(null, "", "   "))).isEmpty();
    }

    @Test
    void rejectsMandatoryPhases() {
        for (PipelineRunPhase phase : PipelineRunPhase.values()) {
            if (phase.isOptional()) {
                continue;
            }
            assertThatThrownBy(() -> SkipPhasesParser.parse(Set.of(phase.name())))
                    .as("%s must not be skippable", phase)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported phase");
        }
    }

    @Test
    void rejectsNamesThatAreNotPhasesAtAll() {
        assertThatThrownBy(() -> SkipPhasesParser.parse(Set.of("TRANSCRIBBE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Allowed: TRANSCRIBE");
    }

    @Test
    void optionalPhasesAreExactlyTheRerunnableOnes() {
        // VideoPhaseRunnerService used to carry its own copy of this set.
        assertThat(PipelineRunPhase.optionalPhases()).containsExactly(
                PipelineRunPhase.TRANSCRIBE, PipelineRunPhase.DIARIZE, PipelineRunPhase.FRAME_SAMPLE,
                PipelineRunPhase.OCR, PipelineRunPhase.FUSE, PipelineRunPhase.KNOWLEDGE,
                PipelineRunPhase.CONTEXT);
    }
}

package com.tradinglabs.vidingest.pipeline.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The column that survives the request. A retry reads this set back to reproduce the run it is
 * retrying, so a lossy round-trip re-enables a phase the operator turned off.
 */
class PhaseSetConverterTest {

    private final PhaseSetConverter converter = new PhaseSetConverter();

    @Test
    void roundTripsInPipelineOrderWhateverOrderItWasGiven() {
        Set<PipelineRunPhase> given = new LinkedHashSet<>(
                Set.of(PipelineRunPhase.KNOWLEDGE, PipelineRunPhase.OCR, PipelineRunPhase.DIARIZE));

        String column = converter.convertToDatabaseColumn(given);

        assertThat(column).isEqualTo("DIARIZE,OCR,KNOWLEDGE");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(given);
    }

    /** NULL is every row written before the column existed: nothing skipped, not "unknown". */
    @Test
    void readsNullAndBlankAsNothingSkipped() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
        assertThat(converter.convertToEntityAttribute(",,")).isEmpty();
    }

    /** Empty writes NULL rather than '', so one meaning has one representation. */
    @Test
    void writesNullForAnEmptySet() {
        assertThat(converter.convertToDatabaseColumn(Set.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    /** A name from a newer version must not make the run unreadable after a downgrade. */
    @Test
    void ignoresAPhaseNameItDoesNotKnow() {
        assertThat(converter.convertToEntityAttribute("OCR,TELEPORT,KNOWLEDGE"))
                .isEqualTo(EnumSet.of(PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE));
    }
}

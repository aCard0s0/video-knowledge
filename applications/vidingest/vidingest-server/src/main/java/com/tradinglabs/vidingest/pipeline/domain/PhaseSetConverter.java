package com.tradinglabs.vidingest.pipeline.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A run's phase opt-out list, in one comma-separated column.
 *
 * <p>Persisting it at all is the point. {@code skipPhases} used to exist only in the in-memory
 * {@code PipelinePhaseContext}, so nothing could answer "what was this run configured to do?" once
 * the request was over — and both retry endpoints take the set from the request body, so a client
 * that sent an empty list silently turned every enrichment phase back on. A run that deliberately
 * skipped OCR came back doing OCR.
 *
 * <p>A column and not a child table: the set is at most seven names, always read and written whole,
 * and never queried by member. NULL and {@code ''} both mean nothing was skipped, which is what
 * every row written before the column existed meant.
 */
@Slf4j
@Converter(autoApply = false)
public class PhaseSetConverter implements AttributeConverter<Set<PipelineRunPhase>, String> {

    @Override
    public String convertToDatabaseColumn(Set<PipelineRunPhase> phases) {
        if (phases == null || phases.isEmpty()) {
            return null;
        }
        // Enum order, so the same set always stores the same string.
        return EnumSet.copyOf(phases).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<PipelineRunPhase> convertToEntityAttribute(String column) {
        EnumSet<PipelineRunPhase> phases = EnumSet.noneOf(PipelineRunPhase.class);
        if (column == null || column.isBlank()) {
            return phases;
        }
        for (String name : column.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                phases.add(PipelineRunPhase.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                // A row written by a newer version naming a phase this one does not have. Loud, but
                // not fatal: failing the read would make every screen that shows the run 500 after
                // a downgrade, and the run still has to be readable.
                log.warn("Unknown phase '{}' in skip_phases='{}' — ignoring", trimmed, column);
            }
        }
        return phases;
    }
}

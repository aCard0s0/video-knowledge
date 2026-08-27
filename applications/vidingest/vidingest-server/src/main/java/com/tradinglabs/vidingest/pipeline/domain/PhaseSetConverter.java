package com.tradinglabs.vidingest.pipeline.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
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
        if (column == null || column.isBlank()) {
            return EnumSet.noneOf(PipelineRunPhase.class);
        }
        // An unknown name throws, and is meant to: there is one jar, so a new phase constant ships
        // with the code that writes it, and a value this enum does not have is a corrupt row.
        return Arrays.stream(column.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(PipelineRunPhase::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PipelineRunPhase.class)));
    }
}

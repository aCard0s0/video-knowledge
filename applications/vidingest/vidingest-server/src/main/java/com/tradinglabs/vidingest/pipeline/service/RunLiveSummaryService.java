package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository.PipelineRunLiveSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunLiveSummaryService {

    private final PipelineRunRepository pipelineRunRepository;

    @Transactional(readOnly = true)
    public List<RunSummary> listLiveSummariesInOrder(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        List<UUID> uniqueIds = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueIds.isEmpty()) return List.of();

        List<PipelineRunLiveSummaryRow> rows = pipelineRunRepository.findLiveSummaryByIdIn(uniqueIds);
        Map<UUID, PipelineRunLiveSummaryRow> rowById = new HashMap<>();
        for (PipelineRunLiveSummaryRow row : rows) {
            if (row == null || row.getId() == null) continue;
            rowById.put(row.getId(), row);
        }

        List<RunSummary> result = new ArrayList<>(uniqueIds.size());
        for (UUID id : uniqueIds) {
            PipelineRunLiveSummaryRow row = rowById.get(id);
            if (row == null) continue;
            result.add(toSummary(row));
        }
        return result;
    }

    /**
     * The polling projection carries status, phase, error and the two instants — nothing about the
     * video. Those fields are {@code null} here, which is the honest answer: this row does not know
     * them. They used to be {@code ""}, indistinguishable from a run whose video genuinely has no
     * title, so a caller could not tell "not carried by this endpoint" from "empty".
     */
    private RunSummary toSummary(PipelineRunLiveSummaryRow row) {
        return new RunSummary(
                row.getId(),
                row.getStatus() != null ? row.getStatus().name() : null,
                row.getPhase() != null ? row.getPhase().name() : null,
                row.getErrorCode() != null ? row.getErrorCode().name() : null,
                row.getError(),
                null,
                null,
                null,
                null,
                0,
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }
}

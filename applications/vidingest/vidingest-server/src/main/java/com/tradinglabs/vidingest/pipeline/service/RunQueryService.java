package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunQueryService {

    private final PipelineRunRepository pipelineRunRepository;

    @Transactional(readOnly = true)
    public List<PipelineRun> listPipelineRunsByStatus(RunStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status is required; use listPipelineRunsPage for unfiltered listings");
        }
        return pipelineRunRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * Sortable columns, by wire name.
     *
     * A whitelist rather than a pass-through: {@code Sort.by} takes a JPA property name, so an
     * unknown one is a 500 from deep inside the persistence layer and an attacker-controlled one is
     * a way to order by, and therefore probe, any field on the entity. Anything not named here
     * falls back to the default rather than failing the request — the sort is a view preference,
     * not part of what was asked for.
     */
    private static final List<String> SORTABLE = List.of("createdAt", "updatedAt");
    private static final String DEFAULT_SORT = "createdAt";

    @Transactional(readOnly = true)
    public Page<PipelineRun> listPipelineRunsPage(String status, Integer page, Integer size, String sortBy) {
        // Null-checked first: List.of() throws NPE on contains(null), and the controller default
        // only covers the HTTP path — an internal caller can still pass nothing.
        String property = sortBy != null && SORTABLE.contains(sortBy) ? sortBy : DEFAULT_SORT;
        Sort sort = Sort.by(Sort.Direction.DESC, property);
        RunStatus runStatus = parseStatus(status);

        int pageValue = page != null ? Math.max(0, page) : 0;
        int sizeValue = size != null ? Math.clamp(size, 1, 200) : 50;
        var pageable = PageRequest.of(pageValue, sizeValue, sort);

        if (runStatus == null) {
            return pipelineRunRepository.findAll(pageable);
        }
        return pipelineRunRepository.findByStatus(runStatus, pageable);
    }

    @Transactional(readOnly = true)
    public PipelineRun getPipelineRun(UUID runId) {
        return pipelineRunRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    private RunStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return RunStatus.valueOf(status.toUpperCase(Locale.ROOT));
    }
}


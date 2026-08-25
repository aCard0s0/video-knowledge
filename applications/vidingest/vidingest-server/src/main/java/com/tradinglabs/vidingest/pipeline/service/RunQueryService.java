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

    @Transactional(readOnly = true)
    public Page<PipelineRun> listPipelineRunsPage(String status, Integer page, Integer size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
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


package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.videos.repo.RunVideoPreview;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RunSummaryPageService {

    private final RunQueryService runQueryService;
    private final VideoRepository videoRepository;
    private final RunSummaryMapper runSummaryMapper;

    @Transactional(readOnly = true)
    public PageResponse<RunSummary> list(String status, Integer page, Integer size, String sortBy) {
        Page<PipelineRun> pageResult = runQueryService.listPipelineRunsPage(status, page, size, sortBy);

        List<PipelineRun> runs = pageResult.getContent();
        List<UUID> runIds = runs.stream()
                .map(PipelineRun::getId)
                .filter(id -> id != null)
                .toList();

        Map<UUID, RunVideoPreview> previewByRunId = new HashMap<>();
        Map<UUID, Integer> videoCountByRunId = new HashMap<>();
        if (!runIds.isEmpty()) {
            for (RunVideoPreview row : videoRepository.findRunVideoPreviews(runIds)) {
                if (row == null || row.runId() == null) continue;
                UUID runId = row.runId();
                videoCountByRunId.merge(runId, 1, Integer::sum);

                RunVideoPreview existingPreview = previewByRunId.get(runId);
                if (existingPreview == null || RunVideoPreview.PREVIEW_ORDER.compare(row, existingPreview) < 0) {
                    previewByRunId.put(runId, row);
                }
            }
        }

        List<RunSummary> items = runs.stream()
                .map(run -> {
                    UUID id = run.getId();
                    RunVideoPreview preview = id != null ? previewByRunId.get(id) : null;
                    int videoCount = (id != null) ? videoCountByRunId.getOrDefault(id, 0) : 0;
                    return runSummaryMapper.toSummary(run, preview, videoCount);
                })
                .toList();

        return new PageResponse<>(items, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements());
    }
}


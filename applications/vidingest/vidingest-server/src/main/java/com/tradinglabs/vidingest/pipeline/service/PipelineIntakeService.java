package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse.ItemResult;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse.ItemStatus;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.util.VideoUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gateway for batch pipeline-run creation. Validates URLs, deduplicates, then delegates to
 * {@link PipelineService}. All three entry points hit this service: {@code PipelineController}
 * (REST), {@code McpIngestTools} (MCP, via {@code VidingestClient}), and
 * {@code YoutubeChannelCommandService} (channel-driven).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineIntakeService {

    private final PipelineService pipelineService;

    public CreatePipelineRunResponse intake(List<String> rawUrls, Set<PipelineRunPhase> skipPhases) {
        if (rawUrls == null || rawUrls.isEmpty()) {
            return new CreatePipelineRunResponse(null, List.of(new ItemResult(
                    null,
                    ItemStatus.REJECTED,
                    null,
                    "urls must not be empty"
            )));
        }

        List<ItemResult> items = new ArrayList<>(rawUrls.size());
        Map<String, Integer> acceptedIndexByUrl = new HashMap<>();
        LinkedHashSet<String> uniqueUrls = new LinkedHashSet<>();

        for (String rawUrl : rawUrls) {
            String url = rawUrl != null ? rawUrl.trim() : null;
            Optional<String> rejection = VideoUrlValidator.validate(url);
            if (rejection.isPresent()) {
                String displayUrl = (url == null || url.isBlank()) ? rawUrl : url;
                items.add(new ItemResult(displayUrl, ItemStatus.REJECTED, null, rejection.get()));
                continue;
            }
            if (!uniqueUrls.add(url)) {
                items.add(new ItemResult(url, ItemStatus.REJECTED, null, "duplicate url in request"));
                continue;
            }
            acceptedIndexByUrl.put(url, items.size());
            items.add(new ItemResult(url, ItemStatus.ACCEPTED, null, null));
        }

        if (uniqueUrls.isEmpty()) {
            log.warn("Pipeline intake rejected all urls. rejectedCount={}", items.size());
            return new CreatePipelineRunResponse(null, items);
        }

        PipelineService.BatchEnqueueResult result = pipelineService.enqueuePipelineRunBatch(
                List.copyOf(uniqueUrls),
                skipPhases
        );
        for (PipelineService.EnqueuedItem accepted : result.items()) {
            Integer idx = acceptedIndexByUrl.get(accepted.url());
            if (idx == null) continue;
            items.set(idx, new ItemResult(
                    accepted.url(),
                    ItemStatus.ACCEPTED,
                    accepted.itemId().toString(),
                    null
            ));
        }
        int rejected = items.size() - result.items().size();
        log.info("Pipeline intake accepted={} rejected={} runId={}", result.items().size(), rejected, result.runId());
        return new CreatePipelineRunResponse(result.runId().toString(), items);
    }
}

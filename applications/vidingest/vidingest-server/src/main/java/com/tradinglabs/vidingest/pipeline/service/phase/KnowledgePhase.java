package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Knowledge-extraction phase (M6). Reads the multimodal segments produced by M5 and asks
 * the configured chat LLM to emit typed knowledge units; persists them with embeddings.
 *
 * <p>Gates on:
 * <ul>
 *   <li>{@code vidingest.knowledge.enabled} (operator master switch)</li>
 *   <li>{@code !ctx.skipKnowledge} (per-run opt-out)</li>
 * </ul>
 *
 * <p>The fusion phase (M5) is independent of this gate: fusion runs unconditionally when
 * its own master switch is on, so the multimodal_segments table populates even when
 * knowledge extraction is skipped. That keeps the M7 enhanced CONTEXT phase wired up
 * regardless of whether the operator wants LLM extraction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class KnowledgePhase implements PipelinePhase {

    private final KnowledgeExtractionService knowledgeExtractionService;
    private final KnowledgeExtractionConfig knowledgeExtractionConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.KNOWLEDGE;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return knowledgeExtractionConfig.isEnabled() && !ctx.isSkipKnowledge();
    }

    @Override
    public void execute(PipelinePhaseContext ctx) {
        Video video = ctx.getVideo();
        if (video == null) {
            log.warn("KnowledgePhase: ctx.video is null, skipping. runId={}, itemId={}",
                    ctx.getRunId(), ctx.getItemId());
            return;
        }
        knowledgeExtractionService.extractKnowledge(video);
    }
}

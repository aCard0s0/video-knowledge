package com.tradinglabs.vidingest.api.health;

import java.util.Map;

/**
 * Which optional phases this deployment will actually execute, keyed by {@code PipelineRunPhase}
 * name.
 *
 * <p>A phase can be turned off two ways and the console has to tell them apart: the operator can
 * opt one run out of it ({@code skipPhases}), and the deployment can disable it outright
 * ({@code vidingest.<phase>.enabled}, which most enrichment phases default to {@code false}).
 * Only the first was visible from the API, so the ingest screen's phase picker showed OCR and
 * KNOWLEDGE ticked and "will run" on a deployment that had them off, and the lane then drew them
 * as phases the operator had chosen to skip.
 *
 * @param phases phase name → whether it is enabled on this deployment
 */
public record PhaseAvailability(Map<String, Boolean> phases) {
}

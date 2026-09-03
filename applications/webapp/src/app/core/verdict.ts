import { CreatePipelineRunResponse, ItemResult } from '../api/generated';

/**
 * What a create-or-retry response actually says, per URL.
 *
 * **A 2xx does not mean the work was queued.** `PipelineService.enqueueRetryBatch` answers with a
 * per-item verdict — already running, was cancelled, no URL left to fetch, duplicate in request —
 * and every item can come back REJECTED under a 202. `POST /pipelines` goes further and answers
 * **400 with this same body** when every URL was rejected, so the reasons arrive on a response that
 * reads as a plain HTTP failure.
 *
 * Four screens read that body and each wrote the filter inline, with the only shared helper living
 * in `features/ingest` where nothing else could import it without reaching across a feature
 * boundary. The rule is one rule, so it lives in one place.
 */
export function acceptedOf(response: CreatePipelineRunResponse | null | undefined): ItemResult[] {
  return (response?.items ?? []).filter((i) => i.status === 'ACCEPTED');
}

/** The refusals, with the server's reason on each — what `vk-rejects` renders. */
export function rejectsOf(response: CreatePipelineRunResponse | null | undefined): ItemResult[] {
  return (response?.items ?? []).filter((i) => i.status === 'REJECTED');
}

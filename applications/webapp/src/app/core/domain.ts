/**
 * Server enums mirrored by hand.
 *
 * The OpenAPI spec types almost every one of these as a plain `string` — springdoc emits enum
 * schemas only for `KnowledgeUnitType` and `ItemResult.status` — so the generated client cannot
 * give us unions. Source of truth is the server:
 *   pipeline/domain/{RunStatus,PipelineRunPhase,PipelineErrorCode,PipelineRunItemEventType}.java
 *   videos/domain/VideoStatus.java
 *   vidingest-api .../api/knowledge/KnowledgeUnitType.java  (the API module, not the server)
 *
 * Two server enums are deliberately *not* mirrored as lists: `YoutubeChannelStatus` and
 * `TranscriptionStatus` are only ever rendered, never enumerated, so their values live as cases in
 * `statusVar` below. A new constant on either needs a `case`, not an array — and `statusVar`
 * already falls through to the neutral spine, so forgetting costs a colour and nothing else.
 *
 * `KNOWLEDGE_TYPES` is mirrored even though the generator does emit that one: it arrives twice,
 * per property, as `KnowledgeUnitDtoTypeEnum` and `SearchKnowledgeHitTypeEnum`. Picking either
 * would bind the video-detail filter to one DTO's name, and a TS `enum` gives `string[]` from
 * `Object.values`, not the literal union `KnowledgeType` is.
 *
 * ponytail: hand-mirrored, so a new server constant renders as unknown until this file is
 * updated. Every consumer falls through to a neutral style rather than throwing. Generate from
 * the spec instead once springdoc emits these as enums.
 */

export const RUN_STATUSES = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED'] as const;
export type RunStatus = (typeof RUN_STATUSES)[number];

/** Full enum, in pipeline order. CREATED and DONE are run markers, not phases. */
export const ALL_PHASES = [
  'CREATED',
  'METADATA',
  'DOWNLOAD',
  'PERSIST',
  'TRANSCRIBE',
  'DIARIZE',
  'FRAME_SAMPLE',
  'OCR',
  'FUSE',
  'KNOWLEDGE',
  'CONTEXT',
  'DONE',
] as const;
export type Phase = (typeof ALL_PHASES)[number];

/** The ten real steps — what the lane renders. */
export type LanePhase = Exclude<Phase, 'CREATED' | 'DONE'>;
export const LANE_PHASES: LanePhase[] = ALL_PHASES.filter(
  (p): p is LanePhase => p !== 'CREATED' && p !== 'DONE',
);

/**
 * True for the ten real steps only.
 *
 * `failedPhase` is not always one of them: an item reaped while it was still queued blames
 * `CREATED`, and a clean finish reports `DONE`. Both are run markers, so anything that treats
 * `failedPhase` as a position — the lane frontier, the "died in X" line — has to ask this first
 * rather than call `indexOf` and get -1.
 */
export function isLanePhase(phase: string | null | undefined): phase is LanePhase {
  return !!phase && (LANE_PHASES as string[]).includes(phase);
}

/**
 * `PipelineRunPhase.isOptional()` — the same set answers "can a run skip this?" and "can it be
 * rerun on its own?", because an optional phase consumes the persisted video row while
 * METADATA/DOWNLOAD/PERSIST consume the source URL. Confirmed live: the server 400s with
 * "Allowed: TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT".
 */
export const OPTIONAL_PHASES = [
  'TRANSCRIBE',
  'DIARIZE',
  'FRAME_SAMPLE',
  'OCR',
  'FUSE',
  'KNOWLEDGE',
  'CONTEXT',
] as const;
export type OptionalPhase = (typeof OPTIONAL_PHASES)[number];

/**
 * Optional phases that another optional phase has to have run first, mirrored from the server's
 * `applies(ctx)` gates: `OcrPhase` also requires FRAME_SAMPLE, `DiarizePhase` also requires
 * TRANSCRIBE. There is nothing to OCR if no frames were sampled, and nothing to diarize without a
 * transcript, so the server skips the dependent phase silently — which made the picker offer OCR
 * as "will run" next to a FRAME_SAMPLE the operator had just turned off.
 */
export const PHASE_REQUIRES: Partial<Record<OptionalPhase, OptionalPhase>> = {
  OCR: 'FRAME_SAMPLE',
  DIARIZE: 'TRANSCRIBE',
};

export const EVENT_TYPES = [
  'ITEM_CREATED',
  'ITEM_PHASE_ENTERED',
  'ITEM_PHASE_COMPLETED',
  'ITEM_VIDEO_ATTACHED',
  'ITEM_COMPLETED',
  'ITEM_FAILED',
  'ITEM_CANCELLED',
  'ITEM_RETRY_REQUESTED',
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

/**
 * `PipelineErrorCode`. Mirrored late: the codes were only ever *rendered* (by `vk-fault`, which
 * takes whatever the server sent), so nothing needed the list until the audit feed offered them as
 * a filter.
 */
export const ERROR_CODES = [
  'DUPLICATE_VIDEO',
  'UPSTREAM_TOOL_FAILURE',
  'TRANSCRIPTION_FAILURE',
  'INVALID_METADATA',
  'UNEXPECTED',
] as const;
export type ErrorCode = (typeof ERROR_CODES)[number];

export const VIDEO_STATUSES = [
  'PENDING',
  'DOWNLOADING',
  'DOWNLOADED',
  'EXTRACTING',
  'TRANSCRIBING',
  'PROCESSING',
  'COMPLETED',
  'FAILED',
] as const;

export const KNOWLEDGE_TYPES = ['PROCEDURE', 'ENTITY', 'TOPIC', 'SUMMARY', 'CLAIM', 'QUESTION'] as const;
export type KnowledgeType = (typeof KNOWLEDGE_TYPES)[number];

/**
 * Which pane on the video screen shows what a phase produced.
 *
 * An item that died in OCR should land the operator on the frames pane, where the rerun sits
 * beside the artifacts it rebuilds. The run screen's phase trail offers the same rerun without
 * leaving the run — it is the same endpoint, gated on the item still carrying a video id
 * (`ON DELETE SET NULL`) — so this link is now for seeing what the phase *produced*, which the run
 * screen cannot show. CONTEXT is absent on purpose: search chunks have no pane, so there is
 * nowhere honest to land.
 */
export const PHASE_PANE: Partial<Record<OptionalPhase, string>> = {
  TRANSCRIBE: 'transcript',
  DIARIZE: 'speakers',
  FRAME_SAMPLE: 'frames',
  OCR: 'frames',
  FUSE: 'fused',
  KNOWLEDGE: 'knowledge',
};

/** CSS custom property holding this status' colour. Unknown values get the neutral spine. */
export function statusVar(status: string | null | undefined): string {
  switch (status) {
    case 'COMPLETED':
    case 'DOWNLOADED':
      return 'var(--st-done)';
    case 'IN_PROGRESS':
    case 'DOWNLOADING':
    case 'EXTRACTING':
    case 'TRANSCRIBING':
    case 'PROCESSING':
    case 'SYNCING':
      return 'var(--st-running)';
    case 'FAILED':
    case 'ERROR':
      return 'var(--st-failed)';
    case 'CANCELLED':
      return 'var(--st-cancelled)';
    case 'READY':
      return 'var(--st-done)';
    case 'PENDING':
    case 'NEW':
      return 'var(--st-pending)';
    default:
      return 'var(--fg-muted)';
  }
}

/** A run is worth polling fast while it can still change. */
export function isLive(status: string | null | undefined): boolean {
  return status === 'PENDING' || status === 'IN_PROGRESS';
}

/** The API returns "" for absent values, not null. */
export function blank(value: string | null | undefined): boolean {
  return value === null || value === undefined || value.trim() === '';
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A whole uuid, which is what every `{id}` path segment and `?runId` actually accepts.
 *
 * Anything else is a 400 carrying a raw Java conversion message, and the ids this console *shows*
 * are `id.slice(0, 8)` — exactly what an operator copies off a row. The audit screen has held this
 * back from its query since it grew a run-id filter; the two screens that carry `?run=` in the URL
 * did not, so a hand-edited or half-copied link put a bare eight characters straight into
 * `GET /pipelines/{id}` and answered with the conversion error instead of the runs board.
 *
 * `syncQueryParams` takes allow-lists, which is the right shape for an enum and no shape at all for
 * a uuid, so the guard lives with the value rather than with the query string.
 */
export function isUuid(value: string | null | undefined): boolean {
  return !!value && UUID.test(value.trim());
}

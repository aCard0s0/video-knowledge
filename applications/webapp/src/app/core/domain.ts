/**
 * Server enums mirrored by hand.
 *
 * The OpenAPI spec types every one of these as a plain `string` — springdoc only emits enum
 * schemas for `KnowledgeUnitType` and `ItemResult.status` — so the generated client cannot give
 * us unions. Source of truth is the server:
 *   pipeline/domain/{RunStatus,PipelineRunPhase,PipelineErrorCode,PipelineRunItemEventType}.java
 *   videos/domain/VideoStatus.java, youtube/domain/YoutubeChannelStatus.java
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

export const KNOWLEDGE_TYPES = ['ENTITY', 'TOPIC', 'SUMMARY', 'CLAIM', 'QUESTION'] as const;
export type KnowledgeType = (typeof KNOWLEDGE_TYPES)[number];

/**
 * Which pane on the video screen shows what a phase produced.
 *
 * An item that died in OCR should land the operator on the frames pane, where the per-phase rerun
 * button already lives. Re-running one phase is deliberately *not* offered from the run screen:
 * the endpoint takes a video id, a run item carries one only while that video row still exists
 * (`ON DELETE SET NULL`), and the video screen can show what the phase produced. Deep-linking the
 * pane removes the only part of that trip that was actually a hunt. CONTEXT is absent on purpose:
 * search chunks have no pane, so there is nowhere honest to land.
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
    case 'DISABLED':
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

import { RunItem, RunItemAuditEvent } from '../api/generated';
import { LANE_PHASES, LanePhase, blank, isLanePhase, isLive } from './domain';
import { msBetween, parseServerTime } from './time';

export type SegmentState = 'done' | 'live' | 'failed' | 'cancelled' | 'skipped' | 'pending';

export interface LaneSegment {
  phase: LanePhase;
  state: SegmentState;
  /** Measured duration; null when the phase never ran. */
  ms: number | null;
}

/**
 * Turns one item's audit trail into a duration-proportional lane.
 *
 * The run's own `phase` cannot drive this: a FAILED run reports `phase: "DONE"` (observed on a
 * real run whose item died in OCR), so the only honest sources are `item.failedPhase` and the
 * ITEM_PHASE_ENTERED / ITEM_PHASE_COMPLETED pairs. Those pairs are also the only place real
 * per-phase durations exist anywhere in the API — which is what separates "hung" from "slow".
 *
 * Only the newest attempt is drawn. A retry re-enters phases it already passed, and overlaying
 * two attempts on one axis would double-count time.
 */
export function buildLane(item: RunItem, events: RunItemAuditEvent[], nowMs: number): LaneSegment[] {
  const mine = events.filter((e) => e.itemId === item.itemId);
  const attempt = mine.reduce((max, e) => Math.max(max, e.attempt ?? 1), item.attempt ?? 1);
  const current = mine.filter((e) => (e.attempt ?? 1) === attempt);

  const entered = new Map<string, string>();
  const completed = new Map<string, string>();
  for (const e of current) {
    if (!e.phase) continue;
    if (e.eventType === 'ITEM_PHASE_ENTERED' && !entered.has(e.phase)) entered.set(e.phase, e.occurredAt ?? '');
    if (e.eventType === 'ITEM_PHASE_COMPLETED') completed.set(e.phase, e.occurredAt ?? '');
  }

  const live = isLive(item.status);

  // CANCELLED is a decision, not a death: DUPLICATE_VIDEO stops an item on purpose, and painting
  // it in the failure red says an operator has something to fix when they do not.
  const stopState: SegmentState = item.status === 'CANCELLED' ? 'cancelled' : 'failed';

  // What the item blamed, and — separately — whether that is a phase the lane actually draws. An
  // item reaped while it was still queued blames CREATED, a run marker: it still sets the frontier
  // (`indexOf` answers -1, so every phase reads "not reached", which is the truth) but it can
  // never take the cap, which is why a FAILED item used to render as ten blank boxes reading
  // "complete". The lane component carries that outcome from `status` instead.
  const blamed = blank(item.failedPhase) || item.failedPhase === 'DONE' ? null : item.failedPhase!;
  const stoppedAt = isLanePhase(blamed) ? blamed : null;

  // Everything past the frontier was never reached; everything before it that has no ENTERED
  // event was deliberately skipped. Same absence, opposite meaning.
  const frontierName = blamed ?? (live ? lastEntered(current) : null);
  const frontier = frontierName ? LANE_PHASES.indexOf(frontierName as LanePhase) : LANE_PHASES.length - 1;

  return LANE_PHASES.map((phase, index) => {
    const start = entered.get(phase);
    const end = completed.get(phase);

    if (!start) {
      return { phase, state: index > frontier ? 'pending' : 'skipped', ms: null };
    }
    if (end) {
      return { phase, state: 'done', ms: msBetween(start, end) };
    }
    if (phase === stoppedAt) {
      return { phase, state: stopState, ms: msBetween(start, item.phaseUpdatedAt) };
    }
    if (live) {
      const from = parseServerTime(start);
      return { phase, state: 'live', ms: from ? nowMs - from.getTime() : null };
    }
    // Entered, never completed, and the item is not live and did not blame this phase: the
    // process died mid-phase. Draw it as stopped rather than silently as complete.
    return { phase, state: stopState, ms: msBetween(start, item.phaseUpdatedAt) };
  });
}

function lastEntered(events: RunItemAuditEvent[]): string | null {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.eventType === 'ITEM_PHASE_ENTERED' && e.phase) return e.phase;
  }
  return null;
}

/** Lanes for a whole run, keyed by item id — the shape both the run screen and ingest render. */
export function buildLanes(
  items: RunItem[],
  events: RunItemAuditEvent[],
  nowMs: number,
): Map<string | undefined, LaneSegment[]> {
  // Grouped once rather than filtered per item. `buildLane` opens with a pass over everything it is
  // handed, so calling it per item was O(items × events): 40 items × 500 events, rebuilt every
  // second while anything is live, is 20,000 comparisons and 160 throwaway arrays a second for
  // lanes that mostly did not change. One pass here leaves each call scanning its own slice.
  const byItem = new Map<string | undefined, RunItemAuditEvent[]>();
  for (const event of events) {
    const mine = byItem.get(event.itemId);
    if (mine) mine.push(event);
    else byItem.set(event.itemId, [event]);
  }
  return new Map(
    items.map((item) => [item.itemId, buildLane(item, byItem.get(item.itemId) ?? [], nowMs)] as const),
  );
}

/** Total measured time across the drawn attempt. */
export function laneTotalMs(segments: LaneSegment[]): number {
  return segments.reduce((sum, s) => sum + (s.ms ?? 0), 0);
}

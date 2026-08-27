import { computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { PipelinesService, RunItem, RunItemAuditEvent } from '../api/generated';
import { auditTail } from './audit';
import { isLive } from './domain';
import { LaneSegment, buildLanes } from './lane';
import { Poller } from './poller';

/**
 * One run, its audit tail, and the lanes drawn from the two.
 *
 * Both screens that draw lanes need the same four moving parts — the run, `auditTail` over its
 * events, a clock that only ticks while something is live, and one `buildLanes` pass keyed by
 * item — and both had them declared inline. That cost two fixes twice: the tail had to stop taking
 * the last *page* of an ascending feed (see `auditTail`) and the lane build had to stop running
 * per template read. A third correction would have landed in one file and not the other.
 *
 * Called from an injection context, like `syncQueryParams` and `clampPage`: the resources die with
 * the component. `runId` returning undefined leaves both idle — the ingest screen has no run to
 * watch until a submit answers.
 *
 * The resources themselves are returned, not just their contents: a template branches on
 * `run.error()` before it may read a value, and `reload()` is what the poller drives.
 */
export function watchRun(runId: () => string | undefined) {
  const pipelines = inject(PipelinesService);
  const poller = inject(Poller);

  const params = () => { const id = runId(); return id ? { id } : undefined; };

  const run = rxResource({ params, stream: ({ params }) => pipelines.getRun(params.id) });
  const audit = rxResource({ params, stream: ({ params }) => auditTail(pipelines, params.id) });

  /**
   * Every read of a resource's value goes through `hasValue()`: `value()` *throws*
   * `ResourceValueError` once the resource has errored, so an unguarded read takes the template
   * down with it — including the error branch that would have explained the failure.
   */
  const detail = computed(() => (run.hasValue() ? run.value() : undefined));
  const items = computed<RunItem[]>(() => detail()?.items ?? []);
  const events = computed<RunItemAuditEvent[]>(() =>
    audit.hasValue() ? (audit.value().items ?? []) : [],
  );

  const auditTotal = computed(() => (audit.hasValue() ? (audit.value().total ?? 0) : 0));
  const auditTruncated = computed(() => auditTotal() > events().length);

  const live = computed(() => items().some((i) => isLive(i.status)));

  /**
   * Lanes are built once per change, not once per template read: calling `buildLane` per item per
   * read was, on a 40-item run, 80 passes over ~640 events a second. The clock is a dependency only
   * while something is actually live — a finished run's lanes never need rebuilding.
   */
  const lanes = computed(() => buildLanes(items(), events(), live() ? poller.now() : 0));

  return {
    run,
    audit,
    detail,
    items,
    events,
    auditTotal,
    auditTruncated,
    live,
    lane: (item: RunItem): LaneSegment[] => lanes().get(item.itemId) ?? [],
    reload: (): void => { run.reload(); audit.reload(); },
  };
}

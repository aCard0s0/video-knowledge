import { Observable, forkJoin, map, of, switchMap } from 'rxjs';

import { PageResponseRunItemAuditEvent } from '../api/generated';

/** `PipelineAuditQueryService.MAX_PAGE_SIZE` — the server clamps anything larger, silently. */
export const AUDIT_PAGE = 500;

/**
 * How far back the tail is followed: four pages, so 2000 events — about ninety items of a batch
 * run — for at most four requests per poll. Past that the run screen's banner reports the
 * shortfall rather than the screen pretending it has the whole trail.
 */
export const AUDIT_MAX_PAGES = 4;

type AuditPage = PageResponseRunItemAuditEvent;

/**
 * The newest window of a run's audit trail, whole.
 *
 * The feed is **ascending** (`findByPipelineRunIdOrderByOccurredAtAscIdAsc`) with offset paging, so
 * page 0 is the oldest window and the events that matter are on the last page. Asking for the last
 * *page* is not asking for the last *window*: it holds `total mod 500` events, which is **1** at
 * the boundary. A 100-URL run emits ~2200 events, and page 4 alone returned 200 — so ninety items
 * had no events in hand at all, and `buildLane` draws an item with no ENTERED events as ten hatched
 * voices reading "skipped — turned off for this run". Every phase those items really ran reported
 * itself as never having happened.
 *
 * So take whole pages from the end and concatenate, keeping the ascending order the lane needs.
 */
export function auditTail(
  pipelines: { auditRun(runId: string, page: number, size: number): Observable<AuditPage> },
  runId: string,
): Observable<AuditPage> {
  return pipelines.auditRun(runId, 0, AUDIT_PAGE).pipe(
    switchMap((first) => {
      const total = first.total ?? 0;
      const last = Math.floor(Math.max(0, total - 1) / AUDIT_PAGE);
      if (last === 0) return of(first);

      const from = Math.max(0, last - (AUDIT_MAX_PAGES - 1));
      const pages: Observable<AuditPage>[] = [];
      for (let page = from; page <= last; page++) {
        // Page 0 is already in hand — that request is what said how many pages there are.
        pages.push(page === 0 ? of(first) : pipelines.auditRun(runId, page, AUDIT_PAGE));
      }
      return forkJoin(pages).pipe(
        map((responses) => ({ ...first, items: responses.flatMap((r) => r.items ?? []), total })),
      );
    }),
  );
}

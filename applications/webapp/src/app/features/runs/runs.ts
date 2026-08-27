import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { ItemResult, PipelinesService, RunSummary } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { RUN_STATUSES, blank, isLanePhase, isLive, statusVar } from '../../core/domain';
import { humanAge, absoluteTime, parseServerTime } from '../../core/time';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { clampPage } from '../../core/paging';
import { shortUrl } from '../../core/url';
import { syncQueryParams } from '../../core/url-state';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { Rejects } from '../../ui/rejects';
import { Fault } from '../../ui/fault';

const PAGE_SIZE = 25;

/**
 * Where the run is, for a run that can still move. A FAILED run reports `phase: "DONE"`, so this
 * asks `isLive` first; the fault row underneath carries the reason once the run cannot move.
 *
 * `CREATED` is not a step either — it is the marker a run wears from `RunLifecycleService.create`
 * until METADATA starts, and `prepareRetry` writes it back, so it is exactly what a run shows for
 * the seconds after the operator presses Retry on this screen. `isLanePhase` is the same guard the
 * run screen uses on `failedPhase`; the honest word for a run holding that marker is that it is
 * queued behind the ingestion semaphore, which is where it actually is.
 */
export function marker(run: RunSummary): string {
  if (!isLive(run.status)) return '—';
  return isLanePhase(run.phase) ? run.phase : 'queued';
}

/**
 * What the retry did, for the line that says so.
 *
 * A retry fired under the FAILED chip takes the run out of the filter it was listed under, so the
 * row disappears — which on its own is indistinguishable from a press that did nothing. Empty when
 * nothing was queued: the rejects panel and the problem panel are already saying why, and a third
 * voice repeating "0" adds nothing.
 */
export function retrySaid(queued: number, asked: number): string {
  return queued ? `Queued ${queued} of ${asked} runs.` : '';
}

/**
 * A terminal run with something to say — the fault row renders under it.
 *
 * Either half is enough: a run can carry a message with no code, and `""` is how the API spells
 * absent for both.
 */
export function hasFault(run: RunSummary): boolean {
  return !isLive(run.status) && (!blank(run.errorCode) || !blank(run.error));
}

@Component({
  selector: 'vk-runs',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, Rejects, Fault],
  templateUrl: './runs.html',
  styleUrl: './runs.scss',
})
export class Runs {
  private readonly pipelines = inject(PipelinesService);
  protected readonly poller = inject(Poller);

  protected readonly statuses = ['ALL', ...RUN_STATUSES];
  protected readonly status = signal('ALL');
  protected readonly page = signal(0);
  /**
   * Which age orders the list, and it is the *server* that orders it.
   *
   * Sorting the 25 rows in hand would answer "what moved most recently" with "of the 25 oldest-
   * created, which moved last" — a different question, and wrong in the way that looks right.
   * `RunQueryService` whitelists the two columns; anything else falls back to `createdAt`.
   */
  protected readonly sortBy = signal('createdAt');
  protected readonly size = PAGE_SIZE;

  /**
   * Two status queries, not `live=true`: on the server that flag is only honoured together with
   * `ids` (it switches to "give me live summaries for these known runs, in order") and otherwise
   * falls through to the plain paged list — so asking for live without ids returns everything,
   * COMPLETED runs included. Asking for the two live statuses is the honest question.
   */
  protected readonly running = rxResource({ stream: () => this.pipelines.listRuns('IN_PROGRESS', 0, PAGE_SIZE) });
  protected readonly pending = rxResource({ stream: () => this.pipelines.listRuns('PENDING', 0, PAGE_SIZE) });

  /**
   * One extra one-row query so the FAILED chip can carry a count: that number is the whole reason
   * this screen gets opened, and a select hides it behind a click.
   */
  protected readonly failedCount = rxResource({
    stream: () => this.pipelines.listRuns('FAILED', 0, 1),
  });

  protected readonly history = rxResource({
    params: () => ({ status: this.status(), page: this.page(), sortBy: this.sortBy() }),
    stream: ({ params }) =>
      this.pipelines.listRuns(params.status, params.page, PAGE_SIZE, undefined, undefined, params.sortBy),
  });

  constructor() {
    syncQueryParams(
      { status: this.status, page: this.page, sortBy: this.sortBy },
      { status: 'ALL', sortBy: 'createdAt' },
    );
    // Retrying the FAILED runs on a page empties it out from under the FAILED filter.
    clampPage(this.page, PAGE_SIZE, this.history);
    this.poller.every(
      () => (this.liveRuns().length > 0 ? POLL_LIVE : POLL_IDLE),
      () => {
        this.running.reload();
        this.pending.reload();
        this.history.reload();
        this.failedCount.reload();
      },
    );
  }

  protected readonly liveRuns = computed(() =>
    [...(valueOf(this.running)?.items ?? []), ...(valueOf(this.pending)?.items ?? [])].sort(
      (a, b) => (parseServerTime(b.createdAt)?.getTime() ?? 0) - (parseServerTime(a.createdAt)?.getTime() ?? 0),
    ),
  );
  protected readonly historyRuns = computed(() => valueOf(this.history)?.items ?? []);
  /**
   * The live runs the history is not already showing.
   *
   * Under ALL — the filter the screen opens on — every running run is in both tables, so the same
   * id sat on screen twice. Matching by id rather than by filter is what makes it exact: a live run
   * on page 2 is genuinely not below, and PENDING under the IN_PROGRESS chip is not either.
   */
  protected readonly liveElsewhere = computed(() => {
    const below = new Set(this.historyRuns().map((r) => r.id));
    return this.liveRuns().filter((r) => !below.has(r.id));
  });
  protected readonly total = computed(() => valueOf(this.history)?.total ?? 0);
  protected readonly failure = computed(
    () => this.retryFailure() ?? firstFailure(this.history, this.running, this.pending),
  );

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly marker = marker;
  protected readonly hasFault = hasFault;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected label(run: RunSummary): string {
    if (!blank(run.videoTitle)) return run.videoTitle!;
    if ((run.videoCount ?? 0) > 1) return `${run.videoCount} URLs`;
    return blank(run.videoUrl) ? '—' : shortUrl(run.videoUrl!);
  }

  protected readonly failed = computed(() => valueOf(this.failedCount)?.total ?? 0);
  /**
   * What "retry all" would actually take: the FAILED runs on the page in front of the operator, not
   * the `failed()` total behind the chip. The two differ past 25 rows, and a button that silently
   * reached beyond the page would be retrying runs nobody had looked at.
   */
  protected readonly failedHere = computed(() => this.historyRuns().filter((r) => r.status === 'FAILED' && r.id));
  protected readonly retrying = signal<ReadonlySet<string>>(new Set());
  protected readonly retryFailure = signal<ApiFailure | null>(null);
  protected readonly retryRejects = signal<ItemResult[]>([]);
  protected readonly retryOutcome = signal('');

  protected setStatus(value: string): void {
    this.status.set(value);
    this.page.set(0);
  }

  /** Re-ordering the whole list invalidates the page you were on, the same as changing the filter. */
  protected setSort(value: string): void {
    if (this.sortBy() === value) return;
    this.sortBy.set(value);
    this.page.set(0);
  }

  /**
   * Retry straight from the board: triage used to cost a navigation before the button appeared.
   *
   * No body at all, and that is the point. There is no phase picker here, so this screen has no
   * business naming phases — and an *empty* `skipPhases` is not silence, it is "run every enabled
   * phase". Sending one re-enabled OCR and KNOWLEDGE for a run created without them. Omitted, the
   * server retries the run with the set stored on the run row.
   *
   * **A 202 does not mean the work was queued.** `PipelineService.enqueueRetryBatch` answers with a
   * per-item verdict: an item that is already running, was cancelled, or has no URL left to fetch
   * comes back REJECTED with a reason, and every item can be. Discarding that body made a refusal
   * indistinguishable from a retry — the row simply stayed FAILED and the operator pressed again.
   */
  protected retry(run: RunSummary): void {
    if (run.id) this.send([run.id]);
  }

  /**
   * Every FAILED run on this page, in one press.
   *
   * The FAILED chip carries the count because that number is why the screen gets opened — and then
   * clearing it cost one press and one round-trip per row, fourteen of them, with every other Retry
   * button disabled in between. The runs are independent POSTs, so they go out together and the
   * server's own `vidingest.ingestion.concurrency` semaphore decides how many actually run at once.
   */
  protected retryAll(): void {
    const ids = this.failedHere().map((r) => r.id!);
    if (ids.length) this.send(ids);
  }

  /**
   * One retry or fourteen, same path.
   *
   * Every request is caught individually: `forkJoin` abandons the whole batch on the first error,
   * and one run that stopped being FAILED between the page load and the press must not swallow the
   * thirteen that were fine. The two ways a retry can decline stay separate, as everywhere else in
   * this console — an HTTP fault is a `ProblemDetail` and goes to `vk-problem` (first one wins,
   * the `firstFailure` rule), while a 202 carrying REJECTED items is the server declining on
   * purpose and goes to `vk-rejects` in warn.
   *
   * Retry stays disabled everywhere while a batch is in flight. There is one results panel, so a
   * second batch launched over the first would silently replace what the first came back with.
   */
  private send(ids: string[]): void {
    this.retrying.set(new Set(ids));
    this.retryFailure.set(null);
    this.retryRejects.set([]);
    this.retryOutcome.set('');
    forkJoin(
      ids.map((id) =>
        this.pipelines.retryRun(id).pipe(
          map((response) => ({
            queued: (response.items ?? []).some((i) => i.status === 'ACCEPTED'),
            rejects: (response.items ?? []).filter((i) => i.status === 'REJECTED'),
            failure: null as ApiFailure | null,
          })),
          catchError((err: unknown) => of({ queued: false, rejects: [] as ItemResult[], failure: toApiFailure(err) })),
        ),
      ),
    ).subscribe((results) => {
      this.retrying.set(new Set());
      this.retryRejects.set(results.flatMap((r) => r.rejects));
      this.retryFailure.set(results.find((r) => r.failure)?.failure ?? null);
      this.retryOutcome.set(retrySaid(results.filter((r) => r.queued).length, ids.length));
      this.running.reload();
      this.pending.reload();
      this.history.reload();
      this.failedCount.reload();
    });
  }
}

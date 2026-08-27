import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { ItemResult, PipelinesService, RunSummary } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { RUN_STATUSES, blank, isLanePhase, isLive, statusVar } from '../../core/domain';
import { humanAge, absoluteTime, parseServerTime } from '../../core/time';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { clampPage } from '../../core/paging';
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
    params: () => ({ status: this.status(), page: this.page() }),
    stream: ({ params }) => this.pipelines.listRuns(params.status, params.page, PAGE_SIZE),
  });

  constructor() {
    syncQueryParams({ status: this.status, page: this.page });
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
    return run.videoUrl ?? '—';
  }

  protected readonly failed = computed(() => valueOf(this.failedCount)?.total ?? 0);
  protected readonly retrying = signal<string | null>(null);
  protected readonly retryFailure = signal<ApiFailure | null>(null);
  protected readonly retryRejects = signal<ItemResult[]>([]);

  protected setStatus(value: string): void {
    this.status.set(value);
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
    if (!run.id) return;
    this.retrying.set(run.id);
    this.retryFailure.set(null);
    this.retryRejects.set([]);
    this.pipelines.retryRun(run.id).subscribe({
      next: (response) => {
        this.retrying.set(null);
        this.retryRejects.set((response.items ?? []).filter((i) => i.status === 'REJECTED'));
        this.running.reload();
        this.pending.reload();
        this.history.reload();
        this.failedCount.reload();
      },
      error: (err: unknown) => {
        this.retrying.set(null);
        this.retryFailure.set(toApiFailure(err));
      },
    });
  }
}

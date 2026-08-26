import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, of, switchMap } from 'rxjs';

import {
  CreatePipelineRunResponse,
  ItemResult,
  PipelinesService,
  RunItem,
  RunItemAuditEvent,
} from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import {
  OPTIONAL_PHASES,
  OptionalPhase,
  PHASE_PANE,
  RUN_STATUSES,
  blank,
  isLanePhase,
  isLive,
  orDash,
  statusVar,
} from '../../core/domain';
import { absoluteTime, clockTime, humanAge, humanDuration, msBetween } from '../../core/time';
import { LaneSegment, buildLanes, laneTotalMs } from '../../core/lane';
import { ApiFailure, toApiFailure } from '../../core/problem';
import { syncQueryParams } from '../../core/url-state';
import { StatusBadge } from '../../ui/status-badge';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';

/**
 * One request for every lane on the screen: the run-level audit feed carries every item's events,
 * so a 40-item run costs two polls, not forty-one.
 *
 * 500 is the server's own `MAX_PAGE_SIZE` in `PipelineAuditQueryService`, which silently clamps
 * anything larger — asking for 1000 and reporting "showing 500 of N" made the cap look like the
 * client's choice when it was never honoured.
 */
const AUDIT_FETCH = 500;

@Component({
  selector: 'vk-run-detail',
  imports: [RouterLink, StatusBadge, Lane, Fault, Problem, PhasePicker],
  templateUrl: './run-detail.html',
  styleUrl: './run-detail.scss',
})
export class RunDetail {
  readonly runId = input.required<string>();

  private readonly pipelines = inject(PipelinesService);
  protected readonly poller = inject(Poller);

  protected readonly run = rxResource({
    params: () => ({ id: this.runId() }),
    stream: ({ params }) => this.pipelines.getRun(params.id),
  });

  /**
   * The trail is ascending, so page 0 is the *oldest* window. On a run past the cap that dropped
   * the ITEM_FAILED — the one event the screen exists to show — while the banner only warned that
   * durations might be partial. Overshoot once and take the last page instead: losing the early
   * phases of a very long run costs a duration, losing the tail costs the diagnosis.
   */
  protected readonly audit = rxResource({
    params: () => ({ id: this.runId() }),
    stream: ({ params }) =>
      this.pipelines.auditRun(params.id, 0, AUDIT_FETCH).pipe(
        switchMap((first) => {
          const total = first.total ?? 0;
          if (total <= AUDIT_FETCH) return of(first);
          return this.pipelines.auditRun(params.id, Math.floor((total - 1) / AUDIT_FETCH), AUDIT_FETCH);
        }),
      ),
  });

  protected readonly retrying = signal(false);
  protected readonly retryFailure = signal<ApiFailure | null>(null);
  protected readonly retryRejects = signal<ItemResult[]>([]);
  /** '' rather than null: syncQueryParams keeps defaults out of the URL by comparing to ''. */
  protected readonly phaseFilter = signal('');

  constructor() {
    syncQueryParams({ item: this.picked, phase: this.phaseFilter });
    this.poller.every(
      () => (isLive(this.detail()?.status) ? POLL_LIVE : POLL_IDLE),
      () => {
        this.run.reload();
        this.audit.reload();
      },
    );
  }

  /**
   * Every read of a resource's value goes through `hasValue()`.
   *
   * `value()` *throws* `ResourceValueError` once the resource is in its error state, so the old
   * `@if (run.value(); as detail)` guard threw before the `@else if (run.error())` branch could
   * be reached: a 404 run sat on "Loading run…" forever with the problem panel unreachable and
   * the console filling once a second, because `intervalMs` above read `value()` too.
   */
  protected readonly detail = computed(() => (this.run.hasValue() ? this.run.value() : undefined));
  protected readonly items = computed<RunItem[]>(() => this.detail()?.items ?? []);
  protected readonly events = computed<RunItemAuditEvent[]>(() =>
    this.audit.hasValue() ? (this.audit.value().items ?? []) : [],
  );
  protected readonly auditTotal = computed(() => (this.audit.hasValue() ? (this.audit.value().total ?? 0) : 0));
  protected readonly auditTruncated = computed(() => this.auditTotal() > this.events().length);

  protected readonly failure = computed(() => {
    const err = this.run.error() ?? this.audit.error();
    return err ? toApiFailure(err) : null;
  });

  /**
   * The operator's pick, in the URL as `?item=`. A plain signal on purpose: it used to be a
   * `linkedSignal` over `items()`, which is a fresh array on every poll, so the selection was
   * thrown away every 15s — and every 2s on a live run. Nothing resets it now; an id belonging to
   * another run simply fails to match below and the default takes over.
   */
  protected readonly picked = signal('');

  /** Opens on the item that needs attention; the operator's pick wins after that. */
  protected readonly selectedId = computed<string | null>(() => {
    const items = this.items();
    const picked = this.picked();
    if (picked && items.some((i) => i.itemId === picked)) return picked;
    const failed = items.find((i) => i.status === 'FAILED');
    const live = items.find((i) => isLive(i.status));
    return (failed ?? live ?? items[0])?.itemId ?? null;
  });

  protected readonly selected = computed(() => this.items().find((i) => i.itemId === this.selectedId()) ?? null);

  protected readonly timeline = computed(() => {
    const id = this.selectedId();
    const phase = this.phaseFilter();
    return this.events()
      .filter((e) => e.itemId === id)
      .filter((e) => !phase || e.phase === phase)
      .slice()
      .reverse(); // newest first: the last thing that happened is what you came to read
  });

  protected readonly retryable = computed(() => this.detail()?.status === 'FAILED');

  /**
   * Item statuses counted, in ramp order. "4 item(s)" does not say how many of them need
   * attention, and the API has answered that question in the same response all along.
   */
  protected readonly tally = computed(() => {
    const counts = new Map<string, number>();
    for (const item of this.items()) {
      const status = item.status ?? '';
      if (status) counts.set(status, (counts.get(status) ?? 0) + 1);
    }
    return RUN_STATUSES.filter((s) => counts.has(s)).map((status) => ({ status, n: counts.get(status)! }));
  });

  /**
   * Which optional phases this run already skipped, as a stable key.
   *
   * A string, not an array, because it is the source of a `linkedSignal` below: the lanes rebuild
   * on every poll (and every second while something is live), and an array would be a fresh
   * reference each time, resetting the picker under the operator's hands.
   */
  private readonly skippedByRun = computed(() => {
    const drawn = this.items().find((item) => this.lane(item).length > 0);
    if (!drawn) return '';
    return this.lane(drawn)
      .filter((s) => s.state === 'skipped')
      .map((s) => s.phase as string)
      .filter((phase) => (OPTIONAL_PHASES as readonly string[]).includes(phase))
      .join(',');
  });

  /**
   * Seeded from what the run actually skipped; the operator's edit wins after that.
   *
   * An empty picker meant every retry silently turned the enrichment phases back on — a run that
   * deliberately skipped OCR and KNOWLEDGE would come back doing both, which is a different run
   * than the one being retried.
   */
  protected readonly retrySkips = linkedSignal<string, string[]>({
    source: () => this.skippedByRun(),
    computation: (skipped) => (skipped ? skipped.split(',') : []),
  });

  protected readonly statusVar = statusVar;
  protected readonly live = (item: RunItem) => isLive(item.status);
  protected readonly absoluteTime = absoluteTime;
  protected readonly clockTime = clockTime;
  protected readonly humanDuration = humanDuration;
  protected readonly blank = blank;
  protected readonly orDash = orDash;

  /**
   * Lanes are built once per change, not once per template read: the old code called buildLane
   * twice per item on every clock tick, which on a 40-item run is 80 passes over ~640 events a
   * second. The clock is only a dependency while something is actually live — a finished run's
   * lanes never need rebuilding.
   */
  private readonly clock = computed(() => (this.items().some((i) => isLive(i.status)) ? this.poller.now() : 0));

  private readonly lanes = computed(() => buildLanes(this.items(), this.events(), this.clock()));

  protected lane(item: RunItem): LaneSegment[] {
    return this.lanes().get(item.itemId) ?? [];
  }

  /**
   * Measured lane time, or — for an item that never entered a phase — how long it sat before it
   * was reaped. Summing an all-void lane gives 0, and "0ms" beside an item that waited twelve
   * hours is the wrong answer to the only question the number is there for.
   */
  protected laneTotal(item: RunItem): string {
    const measured = laneTotalMs(this.lane(item));
    if (measured > 0) return humanDuration(measured);
    const idle = msBetween(this.detail()?.createdAt, item.phaseUpdatedAt);
    return idle && idle > 0 ? `${humanDuration(idle)} idle` : '—';
  }

  /**
   * Where the item stopped, in words.
   *
   * `failedPhase` is not always a phase: it is `CREATED` for an item reaped before it ever ran and
   * `DONE` on a clean finish. Neither is a step, so neither is rendered as a place. And a
   * CANCELLED item did not die — DUPLICATE_VIDEO is a decision the pipeline made.
   */
  protected death(item: RunItem): string {
    const phase = item.failedPhase;
    if (blank(phase) || phase === 'DONE') return '';
    if (!isLanePhase(phase)) return 'never started';
    return item.status === 'CANCELLED' ? `stopped in ${phase}` : `died in ${phase}`;
  }

  /**
   * What names this item in a list.
   *
   * Batched URLs share a host and a path, so what tells two items apart is the tail — which is
   * exactly what a tail-truncating ellipsis eats first: four different videos all read
   * "https://www.youtub…" at 390px. The full URL stays in the title attribute.
   */
  protected label(item: RunItem): string {
    if (!blank(item.videoTitle)) return item.videoTitle!;
    const url = item.url ?? '';
    return url.length > 30 ? `…${url.slice(-29)}` : url;
  }

  /**
   * Query params that open the video screen on the pane for the phase that died, so "video →"
   * lands on the artifacts of the failure and the per-phase rerun button beside them.
   */
  protected paneFor(item: RunItem): Record<string, string> {
    const phase = item.failedPhase;
    const pane = isLanePhase(phase) ? PHASE_PANE[phase as OptionalPhase] : undefined;
    return pane ? { pane } : {};
  }

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected gap(event: RunItemAuditEvent, index: number): string {
    const list = this.timeline();
    const previous = list[index + 1];
    if (!previous) return '';
    const ms = msBetween(previous.occurredAt, event.occurredAt);
    return ms === null ? '' : `+${humanDuration(ms)}`;
  }

  protected select(item: RunItem): void {
    this.picked.set(item.itemId ?? '');
    this.phaseFilter.set('');
  }

  protected pickPhase(item: RunItem, phase: string): void {
    this.picked.set(item.itemId ?? '');
    this.phaseFilter.set(this.phaseFilter() === phase ? '' : phase);
  }

  /**
   * The server answers 409 on any retry of a run that is not FAILED, and `retryRejection` refuses
   * a CANCELLED item outright. A button whose only outcome is an error is not an option, so the
   * item gate asks the same question the run gate does instead of only "is it unfinished?".
   */
  protected retryableItem(item: RunItem): boolean {
    return this.retryable() && item.status !== 'COMPLETED' && item.status !== 'CANCELLED' && !this.live(item);
  }

  protected retryRun(): void {
    this.send(this.pipelines.retryRun(this.runId(), { skipPhases: this.retrySkips() }));
  }

  protected retryItem(item: RunItem): void {
    if (!item.itemId) return;
    this.send(this.pipelines.retryRunItem(this.runId(), item.itemId, { skipPhases: this.retrySkips() }));
  }

  private send(request: Observable<CreatePipelineRunResponse>): void {
    this.retrying.set(true);
    this.retryFailure.set(null);
    this.retryRejects.set([]);
    request.subscribe({
      next: (response) => {
        this.retrying.set(false);
        // 202 does not mean the work was queued. `enqueueRetryBatch` answers with REJECTED items
        // and a reason when it could take nothing — already running, already cancelled — and
        // discarding that body made a retry that did nothing at all look like it had worked.
        this.retryRejects.set((response.items ?? []).filter((i) => i.status === 'REJECTED'));
        this.run.reload();
        this.audit.reload();
      },
      error: (err: unknown) => {
        this.retrying.set(false);
        this.retryFailure.set(toApiFailure(err));
      },
    });
  }
}

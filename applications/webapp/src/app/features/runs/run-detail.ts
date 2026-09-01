import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import {
  CreatePipelineRunResponse,
  ItemResult,
  PipelinesService,
  RunItem,
  RunItemAuditEvent,
  VideoPhasesService,
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
  statusVar,
} from '../../core/domain';
import { absoluteTime, clockTime, humanAge, humanDuration, msBetween } from '../../core/time';
import { LaneSegment, Rerun, SegmentState, laneTotalMs, paintRerun } from '../../core/lane';
import { Capabilities } from '../../core/capabilities';
import { watchRun } from '../../core/watch-run';
import { ApiFailure, firstFailure, toApiFailure } from '../../core/problem';
import { syncQueryParams } from '../../core/url-state';
import { StatusBadge } from '../../ui/status-badge';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { ErrorCode } from '../../ui/error-code';
import { rowDisclosure } from '../../core/disclosure';
import { Problem } from '../../ui/problem';
import { Rejects } from '../../ui/rejects';
import { PhasePicker } from '../../ui/phase-picker';

/**
 * What a phase's lane state is called in the trail's Status column.
 *
 * Not the item's status, which is what the per-event feed showed and why every row of a finished
 * run read IN_PROGRESS: that column carried the status the *item* held when the event was written,
 * so twenty rows repeated the state the item was passing through rather than the outcome of the
 * phase on the row. `statusVar` colours the first four; the two voids fall through to neutral,
 * which is the right answer for a phase that has no outcome to report.
 */
const PHASE_STATUS: Record<SegmentState, string> = {
  done: 'COMPLETED',
  live: 'IN_PROGRESS',
  failed: 'FAILED',
  cancelled: 'CANCELLED',
  skipped: 'SKIPPED',
  pending: 'NOT REACHED',
};

/** Re-runs are keyed per item as well as per phase: the operator can switch item between two. */
function rerunKey(itemId: string | undefined, phase: string): string {
  return `${itemId}:${phase}`;
}

@Component({
  selector: 'vk-run-detail',
  imports: [RouterLink, StatusBadge, Lane, Fault, Problem, PhasePicker, Rejects, ErrorCode],
  templateUrl: './run-detail.html',
  styleUrl: './run-detail.scss',
})
export class RunDetail {
  readonly runId = input.required<string>();

  private readonly pipelines = inject(PipelinesService);
  private readonly videoPhases = inject(VideoPhasesService);
  private readonly capabilities = inject(Capabilities);
  protected readonly poller = inject(Poller);

  /**
   * The run, its audit tail and the lanes drawn from both — the same four moving parts the ingest
   * screen watches its own submit with. One request for every lane on the screen, not one per item:
   * the run-level feed carries every item's events, and `auditTail` takes whole pages from the *end*
   * of that ascending feed.
   */
  private readonly watch = watchRun(() => this.runId());

  protected readonly retrying = signal(false);
  protected readonly retryFailure = signal<ApiFailure | null>(null);
  protected readonly retryRejects = signal<ItemResult[]>([]);
  /**
   * What the last action did, for the `role="status"` line — the same voice the runs board's
   * `retrySaid` gives its own retry. A queued retry moves the run back to PENDING and the button
   * out of the head, which on its own is indistinguishable from a press that did nothing. A
   * per-phase re-run repaints its own row, so this line is the one place its rows-written count
   * lands. Empty when a retry queued nothing: the rejects and problem panels are saying why.
   */
  protected readonly said = signal('');

  /** The phase row one press from wiping its artifacts, and the one currently doing it. */
  protected readonly armedPhase = signal<string | null>(null);
  protected readonly runningPhase = signal<string | null>(null);
  /** Every re-run this screen has fired, overlaid on the run's own trail. See {@link Rerun}. */
  protected readonly reruns = signal<Record<string, Rerun>>({});
  /**
   * Which phase row in the trail has its raw events open, in the URL as `?phase=`.
   *
   * '' rather than null: syncQueryParams keeps defaults out of the URL by comparing to ''.
   */
  protected readonly openPhase = signal('');

  constructor() {
    syncQueryParams({ item: this.picked, phase: this.openPhase });
    this.poller.every(
      () => (isLive(this.detail()?.status) ? POLL_LIVE : POLL_IDLE),
      () => this.watch.reload(),
    );
  }

  /** The template branches on `run.error()` before it may read a value — see `watchRun`. */
  protected readonly run = this.watch.run;
  protected readonly detail = this.watch.detail;
  protected readonly items = this.watch.items;
  protected readonly events = this.watch.events;
  protected readonly auditTotal = this.watch.auditTotal;
  protected readonly auditTruncated = this.watch.auditTruncated;
  private readonly runLane = this.watch.lane;

  /**
   * The run's lanes with this screen's re-runs painted over them.
   *
   * `POST /videos/{id}/phases/{phase}/run` records nothing on the run, so `watch.reload()` after
   * one answers with the run byte-identical — the lane and every column of the trail kept showing
   * the original numbers and there was no in-progress state at any point. See `paintRerun`.
   *
   * A computed map rather than a function the template calls: `vk-lane` takes an array input, so
   * rebuilding it per change-detection read would hand the component a fresh reference every pass
   * — the exact cost `watchRun` moved `buildLanes` out of the read path to avoid. The clock is a
   * dependency only while a re-run is actually open, so a screen with none rebuilds nothing.
   */
  private readonly lanes = computed(() => {
    const reruns = this.reruns();
    const items = this.items();
    if (Object.keys(reruns).length === 0) {
      return new Map(items.map((item) => [item.itemId, this.runLane(item)] as const));
    }
    const now = this.runningPhase() ? this.poller.now() : 0;
    return new Map(
      items.map((item) => {
        const painted = this.runLane(item).map((seg) =>
          paintRerun(seg, reruns[rerunKey(item.itemId, seg.phase)], now),
        );
        return [item.itemId, painted] as const;
      }),
    );
  });

  protected readonly lane = (item: RunItem): LaneSegment[] => this.lanes().get(item.itemId) ?? [];

  /**
   * Loads only. Alone among the screens this one gives the retry its *own* adjacent panel rather
   * than a place in a precedence order: a run that will not load and a retry that was refused are
   * two different things to fix, and this screen has room to say both.
   */
  protected readonly failure = computed(() => firstFailure(this.watch.run, this.watch.audit));

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

  /**
   * The raw events behind the one open phase row — the detail under the summary, not the summary.
   *
   * Newest first: the last thing that happened in that phase is what you came to read. Empty
   * while no row is open, which is what keeps the panel a per-phase table rather than the
   * hundred-row event dump it used to be.
   */
  protected readonly timeline = computed(() => {
    const id = this.selectedId();
    const phase = this.openPhase();
    if (!phase) return [];
    return this.events()
      .filter((e) => e.itemId === id && e.phase === phase)
      .slice()
      .reverse();
  });

  /**
   * The trail, one row per phase.
   *
   * Per *event* the panel repeated ENTERED/COMPLETED pairs for every phase and stamped each of
   * them with the item's status at the time — so a finished run's trail was twenty-two rows of
   * which twenty said IN_PROGRESS. The lane already computes what an operator actually reads off
   * that table (did this phase run, how long did it take, where did it stop), so the summary is
   * the lane's own segments in a table; the events stay one press away, per phase.
   */
  protected readonly phaseRows = computed(() => {
    const item = this.selected();
    if (!item) return [];
    const reruns = this.reruns();
    return this.lane(item).map((seg) => ({
      seg,
      status: PHASE_STATUS[seg.state],
      rerun: reruns[rerunKey(item.itemId, seg.phase)] ?? null,
      events: this.events().filter((e) => e.itemId === item.itemId && e.phase === seg.phase).length,
    }));
  });

  protected readonly retryable = computed(() => this.detail()?.status === 'FAILED');

  /**
   * Item statuses counted, in ramp order. "4 item(s)" does not say how many of them need
   * attention, and the API has answered that question in the same response all along.
   */
  protected readonly tally = computed(() =>
    RUN_STATUSES.map((status) => ({ status, n: this.items().filter((i) => i.status === status).length })).filter(
      (count) => count.n > 0,
    ),
  );

  /**
   * Which optional phases this run skips, as a stable key.
   *
   * Straight from `RunDetails.skipPhases` — the set persisted on the run row. This used to be read
   * off the lane, which cannot answer it: a phase *after* the one that failed was never reached,
   * and the lane draws that the same way it draws a phase that was never reached because it was
   * turned off. A run that skipped OCR and died in DOWNLOAD therefore reported nothing skipped, and
   * every retry from here silently switched OCR and KNOWLEDGE back on.
   *
   * A joined string, not the array, because it is the source of a `linkedSignal` below: the array
   * is a fresh reference on every poll and would reset the picker under the operator's hands.
   */
  private readonly skippedByRun = computed(() => (this.detail()?.skipPhases ?? []).join(','));

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

  /** Which trail row has its message open. See `core/disclosure.ts`. */
  protected readonly disclosure = rowDisclosure();

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
    this.openPhase.set('');
    this.armedPhase.set(null);
  }

  protected pickPhase(item: RunItem, phase: string): void {
    this.picked.set(item.itemId ?? '');
    this.togglePhase(phase);
  }

  /**
   * Also disarms. An arm belongs to the row it was pressed on, and moving the selection with the
   * lane leaves that row behind — a second press would then wipe a phase of a *different* item.
   */
  protected togglePhase(phase: string): void {
    this.armedPhase.set(null);
    this.openPhase.set(this.openPhase() === phase ? '' : phase);
  }

  /**
   * Whether the per-phase rerun endpoint will take this row.
   *
   * Three separate answers, all of which have to be yes. `POST /videos/{id}/phases/{phase}/run`
   * takes a *video* id, so an item whose video row is gone (`ON DELETE SET NULL`) has nothing to
   * address; only `OPTIONAL_PHASES` are reachable at all, since METADATA/DOWNLOAD/PERSIST consume
   * the source URL rather than the persisted row and the server 400s on them; and KNOWLEDGE
   * additionally throws Conflict when `vidingest.knowledge.enabled` is off, which `Capabilities`
   * already knows without another request. Every other disabled phase still runs — the rerun is
   * the operator's escape hatch, the same reasoning the video screen's chip row carries.
   */
  protected rerunnable(item: RunItem | null, seg: LaneSegment): boolean {
    if (!item?.videoId || this.live(item)) return false;
    if (!(OPTIONAL_PHASES as readonly string[]).includes(seg.phase)) return false;
    return !(seg.phase === 'KNOWLEDGE' && this.capabilities.disabledOnServer('KNOWLEDGE'));
  }

  protected rerunLabel(phase: string): string {
    if (this.armedPhase() === phase) return 'Wipe & re-run?';
    if (this.runningPhase() === phase) return 'Running…';
    return 'Re-run';
  }

  /**
   * Two presses, like the video screen's chip row: a rerun wipes this video's artifacts for the
   * phase before rebuilding them, so a stray click in a ten-row table costs a transcript and a
   * ten-minute whisper call. Pressing a different row re-arms that one, so there is always a way
   * out besides Cancel.
   */
  protected confirmRerun(item: RunItem, phase: string): void {
    if (this.armedPhase() !== phase) {
      this.armedPhase.set(phase);
      return;
    }
    this.armedPhase.set(null);
    this.rerunPhase(item, phase);
  }

  /**
   * Synchronous and idempotent server-side — each phase wipes and repopulates its own artifacts —
   * so the honest feedback is elapsed time and rows written.
   *
   * The overlay is written *before* the request goes out and again when it answers: the endpoint
   * records nothing on the run, so `watch.reload()` alone would leave the lane and the row exactly
   * as they were and the operator with nothing but a disabled button to go on. The reload still
   * happens — a re-run can move the video's own status, which the item row reads.
   */
  private rerunPhase(item: RunItem, phase: string): void {
    const key = rerunKey(item.itemId, phase);
    const startedMs = Date.now();
    this.runningPhase.set(phase);
    this.retryFailure.set(null);
    this.said.set('');
    this.reruns.update((all) => ({ ...all, [key]: { at: new Date(startedMs).toISOString(), startedMs, ms: null } }));

    this.videoPhases.runVideoPhase(item.videoId!, phase).subscribe({
      next: (result) => {
        this.runningPhase.set(null);
        // The server's own measurement, not the round trip: it is what the phase actually cost,
        // and it is the number the log line beside it carries.
        const ms = result.elapsedMs ?? Date.now() - startedMs;
        this.reruns.update((all) => ({
          ...all,
          [key]: { ...all[key], ms, rows: result.rowsAffected },
        }));
        const rows = result.rowsAffected === null || result.rowsAffected === undefined ? '' : `, ${result.rowsAffected} row(s)`;
        this.said.set(`re-ran ${result.phase}: ${humanDuration(ms)}${rows}`);
        this.watch.reload();
      },
      error: (err: unknown) => {
        this.runningPhase.set(null);
        // Kept rather than dropped: a failed re-run is the state the row should be reporting, and
        // dropping it would silently restore the successful run underneath as if nothing happened.
        this.reruns.update((all) => ({
          ...all,
          [key]: { ...all[key], ms: Date.now() - startedMs, failed: true },
        }));
        this.retryFailure.set(toApiFailure(err));
      },
    });
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
    this.said.set('');
    request.subscribe({
      next: (response) => {
        this.retrying.set(false);
        // 202 does not mean the work was queued. `enqueueRetryBatch` answers with REJECTED items
        // and a reason when it could take nothing — already running, already cancelled — and
        // discarding that body made a retry that did nothing at all look like it had worked.
        const items = response.items ?? [];
        const queued = items.filter((i) => i.status === 'ACCEPTED').length;
        this.retryRejects.set(items.filter((i) => i.status === 'REJECTED'));
        this.said.set(queued ? `Queued ${queued} item(s).` : '');
        this.watch.reload();
      },
      error: (err: unknown) => {
        this.retrying.set(false);
        this.said.set('');
        this.retryFailure.set(toApiFailure(err));
      },
    });
  }
}

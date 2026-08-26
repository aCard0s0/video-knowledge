import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';

import { PipelinesService, RunItem, RunItemAuditEvent } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { blank, isLive, statusVar } from '../../core/domain';
import { absoluteTime, humanAge, humanDuration, msBetween } from '../../core/time';
import { LaneSegment, buildLanes, laneTotalMs } from '../../core/lane';
import { ApiFailure, toApiFailure } from '../../core/problem';
import { StatusBadge } from '../../ui/status-badge';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';

/**
 * One request for every lane on the screen: the run-level audit feed carries every item's events,
 * so a 40-item run costs two polls, not forty-one. A very long trail is capped — and said so out
 * loud, because a silently truncated trail would understate a phase's duration.
 */
const AUDIT_FETCH = 1000;

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

  protected readonly audit = rxResource({
    params: () => ({ id: this.runId() }),
    stream: ({ params }) => this.pipelines.auditRun(params.id, 0, AUDIT_FETCH),
  });

  protected readonly retrying = signal(false);
  protected readonly retryFailure = signal<ApiFailure | null>(null);
  protected readonly retrySkips = signal<string[]>([]);
  protected readonly phaseFilter = signal<string | null>(null);

  constructor() {
    this.poller.every(
      () => (isLive(this.run.value()?.status) ? POLL_LIVE : POLL_IDLE),
      () => {
        this.run.reload();
        this.audit.reload();
      },
    );
  }

  protected readonly items = computed<RunItem[]>(() => this.run.value()?.items ?? []);
  protected readonly events = computed<RunItemAuditEvent[]>(() => this.audit.value()?.items ?? []);
  protected readonly auditTotal = computed(() => this.audit.value()?.total ?? 0);
  protected readonly auditTruncated = computed(() => this.auditTotal() > this.events().length);

  protected readonly failure = computed(() => {
    const err = this.run.error() ?? this.audit.error();
    return err ? toApiFailure(err) : null;
  });

  /** Opens on the item that needs attention; the operator's pick wins after that. */
  protected readonly selectedId = linkedSignal<string | null>(() => {
    const items = this.items();
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

  protected readonly retryable = computed(() => this.run.value()?.status === 'FAILED');

  protected readonly statusVar = statusVar;
  protected readonly live = (item: RunItem) => isLive(item.status);
  protected readonly absoluteTime = absoluteTime;
  protected readonly humanDuration = humanDuration;
  protected readonly blank = blank;

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

  protected laneTotal(item: RunItem): string {
    return humanDuration(laneTotalMs(this.lane(item)));
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
    this.selectedId.set(item.itemId ?? null);
    this.phaseFilter.set(null);
  }

  protected pickPhase(item: RunItem, phase: string): void {
    this.selectedId.set(item.itemId ?? null);
    this.phaseFilter.set(this.phaseFilter() === phase ? null : phase);
  }

  protected retryRun(): void {
    this.send(this.pipelines.retryRun(this.runId(), { skipPhases: this.retrySkips() }));
  }

  protected retryItem(item: RunItem): void {
    if (!item.itemId) return;
    this.send(this.pipelines.retryRunItem(this.runId(), item.itemId, { skipPhases: this.retrySkips() }));
  }

  private send(request: Observable<unknown>): void {
    this.retrying.set(true);
    this.retryFailure.set(null);
    request.subscribe({
      next: () => {
        this.retrying.set(false);
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

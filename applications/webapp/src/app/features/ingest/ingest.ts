import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { CreatePipelineRunResponse, PipelinesService, RunItem } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { isLive, statusVar } from '../../core/domain';
import { LaneSegment, buildLanes } from '../../core/lane';
import { humanAge } from '../../core/time';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { StatusBadge } from '../../ui/status-badge';
import { ApiFailure, toApiFailure } from '../../core/problem';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';

const MAX_URLS = 100; // CreatePipelineRunRequest: @Size(max = 100)

@Component({
  selector: 'vk-ingest',
  imports: [ReactiveFormsModule, RouterLink, Problem, PhasePicker, Lane, Fault, StatusBadge],
  templateUrl: './ingest.html',
  styleUrl: './ingest.scss',
})
export class Ingest {
  private readonly pipelines = inject(PipelinesService);
  protected readonly poller = inject(Poller);

  private readonly urls = new FormControl('', { nonNullable: true });
  /** A real FormGroup, so `(ngSubmit)` has a directive to bind to. */
  protected readonly form = new FormGroup({ urls: this.urls });
  /** One source of truth for the textarea: the control. */
  private readonly raw = toSignal(this.urls.valueChanges, { initialValue: '' });
  protected readonly skipped = signal<string[]>([]);
  protected readonly submitting = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly result = signal<CreatePipelineRunResponse | null>(null);

  /** Recomputed on every keystroke so the count and the rejects are visible before submitting. */
  protected readonly parsed = computed(() => {
    const lines = this.raw()
      .split(/[\s,]+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const valid = lines.filter((l) => /^https?:\/\//i.test(l));
    const invalid = lines.filter((l) => !/^https?:\/\//i.test(l));
    return { valid: [...new Set(valid)], invalid, duplicates: valid.length - new Set(valid).size };
  });

  protected readonly tooMany = computed(() => this.parsed().valid.length > MAX_URLS);
  protected readonly canSubmit = computed(
    () => !this.submitting() && this.parsed().valid.length > 0 && !this.tooMany(),
  );
  protected readonly maxUrls = MAX_URLS;

  /**
   * Watching the run you just started, here. Submitting used to end the screen: the only feedback
   * was a count, and the actual work was one navigation away. Now the accepted items appear below
   * the form as live lanes, so paste → start → watch happens without moving.
   */
  private readonly watching = rxResource({
    params: () => (this.result()?.runId ? { id: this.result()!.runId! } : undefined),
    stream: ({ params }) => this.pipelines.getRun(params.id),
  });

  private readonly watchingAudit = rxResource({
    params: () => (this.result()?.runId ? { id: this.result()!.runId! } : undefined),
    stream: ({ params }) => this.pipelines.auditRun(params.id, 0, 500),
  });

  /** When nothing has been started yet, the column shows what was started last instead of nothing. */
  protected readonly recent = rxResource({ stream: () => this.pipelines.listRuns('ALL', 0, 5) });

  protected readonly watched = computed(() => this.watching.value());
  protected readonly watchedItems = computed<RunItem[]>(() => this.watched()?.items ?? []);
  protected readonly recentRuns = computed(() => this.recent.value()?.items ?? []);

  private readonly clock = computed(() =>
    this.watchedItems().some((i) => isLive(i.status)) ? this.poller.now() : 0,
  );

  private readonly lanes = computed(() =>
    buildLanes(this.watchedItems(), this.watchingAudit.value()?.items ?? [], this.clock()),
  );

  protected readonly statusVar = statusVar;

  protected lane(item: RunItem): LaneSegment[] {
    return this.lanes().get(item.itemId) ?? [];
  }

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected readonly accepted = computed(() => this.result()?.items?.filter((i) => i.status === 'ACCEPTED') ?? []);
  protected readonly rejected = computed(() => this.result()?.items?.filter((i) => i.status === 'REJECTED') ?? []);

  constructor() {
    this.poller.every(
      () => (this.watchedItems().some((i) => isLive(i.status)) ? POLL_LIVE : POLL_IDLE),
      () => {
        if (this.result()?.runId) {
          this.watching.reload();
          this.watchingAudit.reload();
        }
        this.recent.reload();
      },
    );
  }

  protected submit(): void {
    if (!this.canSubmit()) return;
    this.submitting.set(true);
    this.failure.set(null);
    this.result.set(null);

    this.pipelines.createRuns({ urls: this.parsed().valid, skipPhases: this.skipped() }).subscribe({
      next: (response) => {
        this.result.set(response);
        this.submitting.set(false);
        // Keep rejected URLs in the box so they can be fixed and resubmitted; drop the rest.
        const rejects = (response.items ?? []).filter((i) => i.status === 'REJECTED').map((i) => i.url ?? '');
        this.urls.setValue(rejects.join('\n'));
      },
      error: (err: unknown) => {
        this.failure.set(toApiFailure(err));
        this.submitting.set(false);
      },
    });
  }
}

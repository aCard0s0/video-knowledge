import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { CreatePipelineRunResponse, PipelinesService } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { statusVar } from '../../core/domain';
import { watchRun } from '../../core/watch-run';
import { humanAge } from '../../core/time';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { StatusBadge } from '../../ui/status-badge';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
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
  /**
   * The submit error wins — it is the thing the operator just did — but a load failure has to
   * surface too. With the server down this screen used to render its form and its empty
   * "last five runs" column and say nothing at all about why.
   */
  private readonly submitFailure = signal<ApiFailure | null>(null);
  protected readonly failure = computed(() =>
    firstFailure(this.submitFailure, this.recent, this.watch.run, this.watch.audit),
  );
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
   *
   * The same helper the run screen uses, so the audit tail and the lane build are fixed in one
   * place rather than two. Idle until a submit answers: `watchRun` leaves both its resources alone
   * while the id is undefined.
   */
  private readonly watch = watchRun(() => this.result()?.runId);

  /** When nothing has been started yet, the column shows what was started last instead of nothing. */
  protected readonly recent = rxResource({ stream: () => this.pipelines.listRuns('ALL', 0, 5) });

  protected readonly watched = this.watch.detail;
  protected readonly watchedItems = this.watch.items;
  protected readonly lane = this.watch.lane;
  protected readonly recentRuns = computed(() => valueOf(this.recent)?.items ?? []);

  protected readonly statusVar = statusVar;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected readonly accepted = computed(() => this.result()?.items?.filter((i) => i.status === 'ACCEPTED') ?? []);
  protected readonly rejected = computed(() => this.result()?.items?.filter((i) => i.status === 'REJECTED') ?? []);

  constructor() {
    this.poller.every(
      () => (this.watch.live() ? POLL_LIVE : POLL_IDLE),
      () => {
        if (this.result()?.runId) this.watch.reload();
        this.recent.reload();
      },
    );
  }

  protected submit(): void {
    if (!this.canSubmit()) return;
    this.submitting.set(true);
    this.submitFailure.set(null);
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
        this.submitFailure.set(toApiFailure(err));
        this.submitting.set(false);
      },
    });
  }
}

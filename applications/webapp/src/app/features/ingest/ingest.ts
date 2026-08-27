import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { CreatePipelineRunResponse, ItemResult, PipelinesService } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { statusVar } from '../../core/domain';
import { watchRun } from '../../core/watch-run';
import { humanAge } from '../../core/time';
import { syncQueryParams } from '../../core/url-state';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { StatusBadge } from '../../ui/status-badge';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';

const MAX_URLS = 100; // CreatePipelineRunRequest: @Size(max = 100)

/** A line that will not run, and why — the server's rejects and the client's own, one shape. */
export interface Reject {
  url: string;
  reason: string;
}

/** The REJECTED items out of either response shape (202 retry, 200 create, 400 create). */
export function rejectsOf(response: CreatePipelineRunResponse | null): Reject[] {
  return (response?.items ?? [])
    .filter((i: ItemResult) => i.status === 'REJECTED')
    .map((i: ItemResult) => ({ url: i.url ?? '', reason: i.reason ?? 'rejected' }));
}

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
  protected readonly retrying = signal(false);
  /**
   * The failure of whatever the operator just did — submit or retry. One signal for both, because
   * every handler clears it before sending, so it only ever holds the newest action. It goes in
   * front of the load failures with `??`, which is the rule everywhere in this console.
   */
  private readonly actionFailure = signal<ApiFailure | null>(null);
  protected readonly failure = computed(
    () => this.actionFailure() ?? firstFailure(this.recent, this.watch.run, this.watch.audit),
  );
  protected readonly result = signal<CreatePipelineRunResponse | null>(null);

  /**
   * The run being watched, in the URL.
   *
   * It used to live only in `result()`, so a refresh — or a pasted link, or Back — dropped the run
   * the operator had just started and left them to find it again on the runs board. It is the one
   * piece of state on this screen worth a query param: the textarea is a draft and the phase picker
   * describes the *next* run, but the run in flight is what someone would want to reopen.
   */
  private readonly runId = signal('');

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
   * place rather than two. Idle until there is an id: `watchRun` leaves both its resources alone
   * while it is undefined.
   */
  private readonly watch = watchRun(() => this.runId() || undefined);

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

  /**
   * Lines that will not run: the ones the server declined, plus the ones this screen never sent.
   *
   * The client filters non-http lines out of the request, which is why the server's own reject
   * reasons (blank, not http, duplicate in request) are unreachable from here — so without the
   * client's own list this table never rendered at all, and the discarded lines vanished silently.
   */
  private readonly notSent = signal<Reject[]>([]);
  protected readonly rejected = computed(() => [...this.notSent(), ...rejectsOf(this.result())]);

  /** What the retry declined, out of a 202 that still says ACCEPTED on the envelope. */
  protected readonly retryRejects = signal<Reject[]>([]);
  /** Only a FAILED run may be retried — the server answers 409 for anything else. */
  protected readonly canRetry = computed(() => !this.retrying() && this.watched()?.status === 'FAILED');

  /** "started · 2 accepted" is only true for the run this session started; a reopened one is watched. */
  protected readonly headline = computed(() => {
    const response = this.result();
    if (response) return `started · ${this.accepted().length} accepted`;
    return `watching · ${this.runId().slice(0, 8)}`;
  });

  constructor() {
    syncQueryParams({ run: this.runId });
    this.poller.every(
      () => (this.watch.live() ? POLL_LIVE : POLL_IDLE),
      () => {
        if (this.runId()) this.watch.reload();
        this.recent.reload();
      },
    );
  }

  protected submit(): void {
    if (!this.canSubmit()) return;
    this.submitting.set(true);
    this.actionFailure.set(null);
    this.retryRejects.set([]);
    this.result.set(null);

    // Captured before the request: the operator can keep typing while it is in flight, and these
    // are the lines *this* submission left behind.
    const notSent: Reject[] = this.parsed().invalid.map((url) => ({
      url,
      reason: 'not http(s) — not sent',
    }));

    this.pipelines.createRuns({ urls: this.parsed().valid, skipPhases: this.skipped() }).subscribe({
      next: (response) => {
        this.result.set(response);
        this.runId.set(response.runId ?? '');
        this.notSent.set(notSent);
        this.submitting.set(false);
        // Everything that did not start stays in the box so it can be fixed and resubmitted — the
        // server's rejects *and* the lines the client never sent. Dropping the latter deleted the
        // operator's typos along with the record that they had ever been pasted.
        this.urls.setValue([...notSent.map((r) => r.url), ...rejectsOf(response).map((r) => r.url)].join('\n'));
      },
      error: (err: unknown) => {
        this.actionFailure.set(toApiFailure(err));
        this.submitting.set(false);
      },
    });
  }

  /**
   * Retry the run in front of you, without leaving the screen.
   *
   * No request body. `skipPhases` absent means "reuse the run's own set" — sending the phase
   * picker's current value instead would re-enable every enrichment phase this run was deliberately
   * created without, and the picker describes the *next* run, not this one.
   */
  protected retry(): void {
    const id = this.runId();
    if (!id || !this.canRetry()) return;
    this.retrying.set(true);
    this.actionFailure.set(null);
    this.retryRejects.set([]);

    this.pipelines.retryRun(id).subscribe({
      next: (response) => {
        // A 202 does not mean the work was queued: the same body carries REJECTED items with a
        // reason ("already running", "was cancelled"), so the response is read, never discarded.
        this.retryRejects.set(rejectsOf(response));
        this.retrying.set(false);
        this.watch.reload();
      },
      error: (err: unknown) => {
        this.actionFailure.set(toApiFailure(err));
        this.retrying.set(false);
      },
    });
  }
}

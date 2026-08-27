import { Component, computed, effect, inject, signal, viewChild, ElementRef } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { CreatePipelineRunResponse, HealthService, ItemResult, PipelinesService, RunItem, RunSummary } from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { OptionalPhase, blank, statusVar } from '../../core/domain';
import { watchRun } from '../../core/watch-run';
import { absoluteTime, humanAge } from '../../core/time';
import { shortUrl } from '../../core/url';
import { syncQueryParams } from '../../core/url-state';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { StatusBadge } from '../../ui/status-badge';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';

const MAX_URLS = 100; // CreatePipelineRunRequest: @Size(max = 100)
const HTTP_URL = /^https?:\/\//i;

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

/**
 * What the operator typed, read the way the help text promises: **one URL per line**.
 *
 * It used to split the whole box on `/[\s,]+/`, which made every count a count of *tokens* — so
 * pasting `some junk text` reported "3 line(s) are not http(s)" for one line, and the number the
 * operator was meant to reconcile against their paste did not match it. A line may still carry
 * several URLs (a pasted comma list); it is only reported invalid when something on it is not one,
 * and then the whole line is left out rather than half-ingested.
 */
export function parseUrls(raw: string): { valid: string[]; invalid: string[]; duplicates: number } {
  const seen = new Set<string>();
  const valid: string[] = [];
  const invalid: string[] = [];
  let duplicates = 0;

  for (const line of raw.split('\n').map((l) => l.trim()).filter((l) => l.length > 0)) {
    const tokens = line.split(/[\s,]+/).filter(Boolean);
    if (!tokens.every((t) => HTTP_URL.test(t))) {
      invalid.push(line);
      continue;
    }
    for (const url of tokens) {
      if (seen.has(url)) duplicates++;
      else {
        seen.add(url);
        valid.push(url);
      }
    }
  }
  return { valid, invalid, duplicates };
}

@Component({
  selector: 'vk-ingest',
  imports: [ReactiveFormsModule, RouterLink, Problem, PhasePicker, Lane, Fault, StatusBadge],
  templateUrl: './ingest.html',
  styleUrl: './ingest.scss',
})
export class Ingest {
  private readonly pipelines = inject(PipelinesService);
  private readonly health = inject(HealthService);
  private readonly router = inject(Router);
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
   * It used to live only in `result()`, so a refresh — or Back, or a pasted link — dropped the run
   * the operator had just started and left them to find it again on the runs board. It is the one
   * piece of state on this screen worth a query param: the textarea is a draft and the phase picker
   * describes the *next* run, but the run in flight is what someone would want to reopen.
   */
  private readonly runId = signal('');

  /** Focused after a submit answers, so the keyboard lands on the result rather than on nothing. */
  private readonly watchPanel = viewChild<ElementRef<HTMLElement>>('watchPanel');

  /** Recomputed on every keystroke so the count and the rejects are visible before submitting. */
  protected readonly parsed = computed(() => parseUrls(this.raw()));

  protected readonly tooMany = computed(() => this.parsed().valid.length > MAX_URLS);
  protected readonly canSubmit = computed(
    () => !this.submitting() && this.parsed().valid.length > 0 && !this.tooMany(),
  );
  protected readonly maxUrls = MAX_URLS;
  /** Anything the operator should reconcile against what they pasted, rather than a plain count. */
  protected readonly hasDiscards = computed(
    () => this.tooMany() || this.parsed().invalid.length > 0 || this.parsed().duplicates > 0,
  );

  /**
   * Watching the run you just started, here. Submitting used to end the screen: the only feedback
   * was a count, and the actual work was one navigation away. Now the accepted items appear below
   * the form as live lanes, so paste → start → watch happens without moving.
   */
  private readonly watch = watchRun(() => this.runId() || undefined);

  /** When nothing has been started yet, the column shows what was started last instead of nothing. */
  protected readonly recent = rxResource({ stream: () => this.pipelines.listRuns('ALL', 0, 5) });

  /**
   * Which optional phases this deployment will actually execute.
   *
   * Fetched once — it is deployment config, not state, so it cannot change under a session the way
   * a run can. Without it the picker rendered all seven ticked and "will run" while the compose
   * defaults have DIARIZE, FRAME_SAMPLE, OCR and KNOWLEDGE off, and the lane then drew them as
   * phases the *operator* had turned off.
   */
  protected readonly availability = rxResource({ stream: () => this.health.phaseAvailability() });
  protected readonly disabledPhases = computed<OptionalPhase[]>(() => {
    const phases = valueOf(this.availability)?.phases ?? {};
    return Object.entries(phases)
      .filter(([, enabled]) => !enabled)
      .map(([phase]) => phase as OptionalPhase);
  });

  protected readonly watched = this.watch.detail;
  protected readonly watchedItems = this.watch.items;
  protected readonly lane = this.watch.lane;
  protected readonly recentRuns = computed(() => valueOf(this.recent)?.items ?? []);
  /**
   * A failed list is not an empty one. `recentRuns()` falls back to `[]` while the resource is
   * loading *or* errored, so the empty state announced "Nothing ingested yet" over a request that
   * never answered — reassuring, and false. The panel above names the fault; this stops the panel
   * below contradicting it.
   */
  protected readonly recentLoaded = computed(() => this.recent.hasValue());

  protected readonly statusVar = statusVar;
  protected readonly blank = blank;
  protected readonly shortUrl = shortUrl;
  protected readonly absoluteTime = absoluteTime;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  /** Title first, then the URL with its boilerplate off — never the raw URL, which clips its id. */
  protected label(run: RunSummary | RunItem): string {
    return blank(run.videoTitle) ? shortUrl(this.sourceUrl(run)) : run.videoTitle!;
  }

  private sourceUrl(run: RunSummary | RunItem): string {
    return (run as RunSummary).videoUrl ?? (run as RunItem).url ?? '';
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

    /**
     * Move the keyboard onto the result once the panel exists.
     *
     * Submitting disabled the button, which blurs it, and the browser resets focus to `<body>` —
     * so a keyboard operator was returned to the top of the document by the act of succeeding.
     * `preventScroll` is not optional: putting `?run=` in the URL is a navigation, and the router
     * is configured to scroll to top on those, so a focus that scrolled would be undone a moment
     * later (see "The router scrolls to top on every syncQueryParams write" in the Web UI doc).
     */
    effect(() => {
      if (!this.result()) return;
      this.watchPanel()?.nativeElement.focus({ preventScroll: true });
    });
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
        this.accept(response, notSent);
      },
      error: (err: unknown) => {
        // `POST /pipelines` answers **400 with a CreatePipelineRunResponse body** — not a
        // ProblemDetail — when every URL was rejected. Read as an HTTP fault it collapses to
        // "400 Bad Request / Http failure response for …" and throws away the per-URL reasons,
        // which are the entire content of that answer.
        const body = err instanceof HttpErrorResponse ? (err.error as CreatePipelineRunResponse | null) : null;
        if (body?.items?.length) this.accept(body, notSent);
        else this.actionFailure.set(toApiFailure(err));
        this.submitting.set(false);
      },
    });
  }

  /** Shared by the 200 and the all-rejected 400: both carry the same body. */
  private accept(response: CreatePipelineRunResponse, notSent: Reject[]): void {
    // Kept even when `runId` is null — that is the all-rejected 400, whose entire content is the
    // per-URL reasons in `items`. The watch panel keys off `runId`, not this, so an empty id simply
    // leaves the column on its recent-runs branch.
    this.result.set(response);
    this.runId.set(response.runId ?? '');
    this.notSent.set(notSent);
    this.submitting.set(false);
    // Everything that did not start stays in the box so it can be fixed and resubmitted — the
    // server's rejects *and* the lines the client never sent. Dropping the latter deleted the
    // operator's typos along with the record that they had ever been pasted.
    this.urls.setValue([...notSent.map((r) => r.url), ...rejectsOf(response).map((r) => r.url)].join('\n'));
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

  /**
   * A lane segment opens the run screen with the trail already filtered to that phase.
   *
   * The segments are buttons on both screens that draw lanes, but only run detail listened: here
   * they were focusable controls that did nothing, ten per completed item. The trail they filter
   * does not exist on this screen — so the honest destination is the screen where it does, which
   * is also where the operator was heading.
   */
  protected openPhase(item: RunItem, phase: string): void {
    void this.router.navigate(['/runs', this.runId()], {
      queryParams: { item: item.itemId, phase },
    });
  }
}

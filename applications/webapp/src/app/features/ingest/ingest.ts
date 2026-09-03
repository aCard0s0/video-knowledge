import { Component, computed, effect, inject, signal, viewChild, ElementRef } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import {
  CreatePipelineRunResponse,
  ItemResult,
  ItemResultStatusEnum,
  PipelinesService,
} from '../../api/generated';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { blank, statusVar } from '../../core/domain';
import { watchRunFromUrl } from '../../core/watch-run';
import { absoluteTime, humanAge } from '../../core/time';
import { shortUrl } from '../../core/url';
import { RunWatch } from '../../ui/run-watch';
import { StatusBadge } from '../../ui/status-badge';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';
import { Rejects } from '../../ui/rejects';
import { ErrorCode } from '../../ui/error-code';
import { rowDisclosure } from '../../core/disclosure';

const MAX_URLS = 100; // CreatePipelineRunRequest: @Size(max = 100)
const RECENT_SIZE = 50; // one screenful of a range the server now bounds; 200 is the API ceiling
const RANGES = ['today', 'week', 'all'] as const;
type RunRange = (typeof RANGES)[number];
const HTTP_URL = /^https?:\/\//i;

/** The REJECTED items out of either response shape (202 retry, 200 create, 400 create). */
export function rejectsOf(response: CreatePipelineRunResponse | null): ItemResult[] {
  return (response?.items ?? []).filter((i) => i.status === 'REJECTED');
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

  for (const line of raw
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0)) {
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
  imports: [
    ReactiveFormsModule,
    RouterLink,
    Problem,
    PhasePicker,
    RunWatch,
    StatusBadge,
    Rejects,
    ErrorCode,
  ],
  templateUrl: './ingest.html',
  styleUrl: './ingest.scss',
})
export class Ingest {
  private readonly pipelines = inject(PipelinesService);
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
   * Focused after a submit answers, so the keyboard lands on the result rather than on nothing.
   * `read: ElementRef` because the ref names a component now — without it the query hands back the
   * `RunWatch` instance, which has no `nativeElement` to focus.
   */
  private readonly watchPanel = viewChild('watchPanel', { read: ElementRef });

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
   *
   * `watchRunFromUrl` carries the id in the query string as `?run=` — the one piece of state on
   * this screen worth a link, since the textarea is a draft and the phase picker describes the
   * *next* run. The handle goes straight to `vk-run-watch`, which draws the panel.
   */
  protected readonly watch = watchRunFromUrl();
  protected readonly runId = this.watch.runId;

  protected readonly ranges = RANGES;
  protected readonly range = signal<RunRange>('today');

  /**
   * Local midnight, not "24 hours ago": a boundary that slides with the clock moves runs out of
   * "today" while the operator is reading them.
   *
   * Reading `poller.now()` inside a `computed` is safe even though this feeds a request: the value
   * it returns is the same ISO string on every tick, so signal equality stops the change there and
   * the resource's params never move until the day does.
   */
  private readonly since = computed(() => {
    const range = this.range();
    if (range === 'all') return undefined;
    const start = new Date(this.poller.now());
    start.setHours(0, 0, 0, 0);
    if (range === 'week') start.setDate(start.getDate() - 6);
    return start.toISOString();
  });

  /**
   * When nothing has been started yet, the panel shows what has been started instead of nothing.
   *
   * The range is the *server's* now, via `?createdAfter=`: it used to be a client-side cut of one
   * 200-row page, which is a range only while every run fits in that page and silently a window
   * past it. The instant carries an offset because which midnight "today" starts at is this
   * screen's business and not the server's.
   */
  protected readonly recent = rxResource({
    params: () => ({ since: this.since() }),
    stream: ({ params }) =>
      this.pipelines.listRuns('ALL', 0, RECENT_SIZE, undefined, undefined, undefined, params.since),
  });

  protected readonly watched = this.watch.detail;
  protected readonly recentRuns = computed(() => valueOf(this.recent)?.items ?? []);

  /**
   * Whether the range holds runs this page did not carry — `total` is now the count of the *range*
   * rather than of the table, so this is one comparison instead of an inference from the oldest row
   * in hand. Still worth saying: a cap that says nothing reads as "that is all of them".
   */
  protected readonly truncated = computed(
    () => (valueOf(this.recent)?.total ?? 0) > this.recentRuns().length,
  );

  protected readonly statusVar = statusVar;
  protected readonly blank = blank;
  protected readonly shortUrl = shortUrl;
  protected readonly absoluteTime = absoluteTime;

  /** Which recent row has its message open. See `core/disclosure.ts`. */
  protected readonly disclosure = rowDisclosure();

  /** The row is the link to the run; anything in it that is already a control keeps its own click. */
  protected openRun(run: { id?: string }, event: Event): void {
    if ((event.target as HTMLElement).closest('a, button')) return;
    if (run.id) void this.router.navigate(['/runs', run.id]);
  }

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  /** Title first, then the URL with its boilerplate off — never the raw URL, which clips its id. */
  protected label(title: string | undefined, url: string | undefined): string {
    return blank(title) ? shortUrl(url) : title!;
  }


  protected readonly accepted = computed(
    () => this.result()?.items?.filter((i) => i.status === 'ACCEPTED') ?? [],
  );

  /**
   * Lines that will not run: the ones the server declined, plus the ones this screen never sent.
   *
   * The client filters non-http lines out of the request, which is why the server's own reject
   * reasons (blank, not http, duplicate in request) are unreachable from here — so without the
   * client's own list this table never rendered at all, and the discarded lines vanished silently.
   */
  private readonly notSent = signal<ItemResult[]>([]);
  protected readonly rejected = computed(() => [...this.notSent(), ...rejectsOf(this.result())]);

  /** What the retry declined, out of a 202 that still says ACCEPTED on the envelope. */
  protected readonly retryRejects = signal<ItemResult[]>([]);
  /** Only a FAILED run may be retried — the server answers 409 for anything else. */
  protected readonly canRetry = computed(
    () => !this.retrying() && this.watched()?.status === 'FAILED',
  );

  /**
   * "started · 2 accepted" is only true for the run this session started. A run reopened from
   * `?run=` gets no label at all: the panel names the run it is watching, which is the honest
   * thing to say about one this visit did not start.
   */
  protected readonly startedLabel = computed(() =>
    this.result() ? `started · ${this.accepted().length} accepted` : '',
  );

  constructor() {
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
    const notSent: ItemResult[] = this.parsed().invalid.map((url) => ({
      url,
      status: ItemResultStatusEnum.Rejected,
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
        const body =
          err instanceof HttpErrorResponse ? (err.error as CreatePipelineRunResponse | null) : null;
        if (body?.items?.length) this.accept(body, notSent);
        else this.actionFailure.set(toApiFailure(err));
        this.submitting.set(false);
      },
    });
  }

  /** Shared by the 200 and the all-rejected 400: both carry the same body. */
  private accept(response: CreatePipelineRunResponse, notSent: ItemResult[]): void {
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
    this.urls.setValue(
      [...notSent.map((r) => r.url), ...rejectsOf(response).map((r) => r.url)].join('\n'),
    );
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

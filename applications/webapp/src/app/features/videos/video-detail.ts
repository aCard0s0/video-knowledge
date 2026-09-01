import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import {
  KnowledgeService,
  OcrFrameGroup,
  OcrResultDto,
  SpeakerDto,
  SpeakersService,
  VideoMultimodalService,
  VideoPhasesService,
  VideosService,
} from '../../api/generated';
import { KNOWLEDGE_TYPES, OPTIONAL_PHASES, OptionalPhase, statusVar } from '../../core/domain';
import { absoluteTime, humanAge, humanDuration, timecode } from '../../core/time';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { clampPage } from '../../core/paging';
import { Capabilities } from '../../core/capabilities';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { API_V1 } from '../../core/api-base';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { Problem } from '../../ui/problem';
import { syncQueryParams } from '../../core/url-state';
import { rowDisclosure } from '../../core/disclosure';

/**
 * OCR lines shown per frame before "show more".
 *
 * paddleocr returns *every* string it found on the frame, so a slide of a pricing page is 26 reads
 * and a talking head is two — and the row grew to whatever it was handed, making the pane a
 * column of 120px rows with an occasional 1000px one that pushed the rest off screen. Eight is
 * roughly the thumbnail's own height, so the frame is what sets the row height and nothing in the
 * list is taller than it needs to be.
 *
 * Counted, not measured: an item cap needs no ResizeObserver and cannot disagree with itself
 * across a resize. A long line still wraps past the cap, which is the right trade — clipping the
 * text would defeat the pane, whose whole job is checking a read against the frame it came from.
 */
const OCR_LINE_CAP = 8;

const PANE_KEYS = ['transcript', 'frames', 'fused', 'knowledge', 'speakers'] as const;
type Pane = (typeof PANE_KEYS)[number];

type PaneTab = { key: Pane; label: string; count: () => number; resource: { reload(): void } };

const SEG_SIZE = 50;
const FRAME_SIZE = 25;
const FUSED_SIZE = 50;

@Component({
  selector: 'vk-video-detail',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, Icon],
  templateUrl: './video-detail.html',
  styleUrl: './video-detail.scss',
})
export class VideoDetail {
  readonly videoId = input.required<string>();

  private readonly videos = inject(VideosService);
  private readonly artifacts = inject(VideoMultimodalService);
  private readonly knowledgeApi = inject(KnowledgeService);
  private readonly speakersApi = inject(SpeakersService);
  private readonly phases = inject(VideoPhasesService);
  private readonly capabilities = inject(Capabilities);
  private readonly poller = inject(Poller);

  private readonly player = viewChild<ElementRef<HTMLVideoElement>>('player');

  protected readonly pane = signal<Pane>('transcript');
  protected readonly page = signal(0);
  protected readonly knowledgeType = signal<string>('');
  protected readonly cursor = signal(0);

  protected readonly running = signal<string | null>(null);
  protected readonly lastRun = signal<string | null>(null);
  private readonly actionFailure = signal<ApiFailure | null>(null);
  protected readonly renaming = signal<string | null>(null);
  protected readonly renameResult = signal<string | null>(null);

  /** The chip pressed once and awaiting its confirming second press. */
  protected readonly armed = signal<string | null>(null);

  /**
   * How long the in-flight rerun has been running.
   *
   * Its own interval, not `poller.now()`: that clock stops while the operator has polling paused,
   * and the request does not — the same reason the rail's wall clock has one (`app.ts`). Started
   * on the press and cleared on the answer, so an idle screen ticks nothing.
   */
  protected readonly elapsedMs = signal(0);
  private ticker: ReturnType<typeof setInterval> | undefined;

  constructor() {
    /*
      Both params reach something that refuses an unknown value, so both are allow-listed — the
      rule the audit screen's four selects already follow. `?pane=BOGUS` matched no `@switch` case
      and rendered an artifact column that was simply blank, with no tab lit and nothing saying
      why; `?type=BOGUS` goes into `listVideoKnowledge` and comes back a 400 carrying a raw Java
      enum name. An unlisted value is ignored, so the signal keeps its default and the effect drops
      the key from the URL.
    */
    syncQueryParams(
      { pane: this.pane, page: this.page, type: this.knowledgeType },
      { pane: PANE_KEYS, type: ['', ...KNOWLEDGE_TYPES] },
    );
    // One page signal, three paged panes. A pane that is not visible has undefined params, so its
    // resource is idle and holds no page — only the visible one's clamp is ever live.
    clampPage(this.page, SEG_SIZE, this.segments);
    clampPage(this.page, FRAME_SIZE, this.frames);
    clampPage(this.page, FUSED_SIZE, this.fused);
    /*
      This screen used to fetch once and never again.

      The videos list was corrected for exactly this: a row left TRANSCRIBING stayed TRANSCRIBING
      for as long as the screen was open. Opening that row made it worse, not better — the status,
      the artifact counts and the dossier are all on `/detail`, and a video the pipeline is actively
      working on sat frozen while the rail underneath ticked "updated 2.0s ago" and every age on the
      page moved. `Poller` was injected here only to drive those ages.

      Fast only while this video can still change, which is the same question the two list screens
      ask of their rows. The visible pane comes with it: a phase that finishes writes rows the pane
      is already showing a stale count of.
    */
    this.poller.every(
      () => (this.moving() ? POLL_LIVE : POLL_IDLE),
      () => {
        // Not while a rerun is in flight: that request answers with the counts itself and reloads
        // both, and a poll landing mid-wipe would show the artifacts half-deleted.
        if (this.running()) return;
        this.detail.reload();
        this.reloadPane();
      },
    );
    inject(DestroyRef).onDestroy(() => this.stopTicking());
  }

  /**
   * This video can still change, so the cadence is worth the requests. Stated as *not finished*
   * rather than as a list of what moves, so a `VideoStatus` the server adds counts as moving —
   * polling a settled video costs one request, freezing a live one is the defect this closes.
   */
  private readonly moving = computed(() => {
    const status = this.video()?.status;
    return !!status && status !== 'COMPLETED' && status !== 'FAILED';
  });

  protected readonly detail = rxResource({
    params: () => ({ id: this.videoId() }),
    stream: ({ params }) => this.videos.getVideoDetail(params.id),
  });

  // Each pane fetches only while it is the visible one — undefined params keep a resource idle.
  protected readonly segments = rxResource({
    params: () => (this.pane() === 'transcript' ? { id: this.videoId(), page: this.page() } : undefined),
    stream: ({ params }) => this.videos.listTranscriptionSegments(params.id, params.page, SEG_SIZE),
  });

  protected readonly frames = rxResource({
    params: () => (this.pane() === 'frames' ? { id: this.videoId(), page: this.page() } : undefined),
    stream: ({ params }) => this.artifacts.ocrResultsByFramePage(params.id, params.page, FRAME_SIZE),
  });

  /** Which frame has its full OCR read open. One at a time — see `core/disclosure.ts`. */
  protected readonly openFrame = rowDisclosure();

  /** Capped unless this frame is the open one. See {@link OCR_LINE_CAP}. */
  protected ocrLines(frame: OcrFrameGroup): OcrResultDto[] {
    const lines = frame.lines ?? [];
    return this.openFrame.isOpen(frame.frameId) ? lines : lines.slice(0, OCR_LINE_CAP);
  }

  protected hiddenOcrLines(frame: OcrFrameGroup): number {
    return Math.max(0, (frame.lines?.length ?? 0) - OCR_LINE_CAP);
  }

  protected readonly fused = rxResource({
    params: () => (this.pane() === 'fused' ? { id: this.videoId(), page: this.page() } : undefined),
    stream: ({ params }) => this.artifacts.multimodalTimelinePage(params.id, params.page, FUSED_SIZE),
  });

  protected readonly knowledge = rxResource({
    params: () => (this.pane() === 'knowledge' ? { id: this.videoId(), type: this.knowledgeType() } : undefined),
    stream: ({ params }) => this.knowledgeApi.listVideoKnowledge(params.id, (params.type || undefined) as never),
  });

  protected readonly speakers = rxResource({
    params: () => (this.pane() === 'speakers' ? { id: this.videoId() } : undefined),
    stream: ({ params }) => this.speakersApi.listVideoSpeakers(params.id),
  });

  /**
   * The one table of panes: label, count, and the resource behind it. Adding a pane used to mean
   * four lists — this one, the `Pane` union, the resource itself, and a second `key → resource`
   * record inside `reloadPane` holding the same five constants, rebuilt on every call.
   */
  protected readonly panes: PaneTab[] = [
    { key: 'transcript', label: 'Transcript', count: () => this.counts()?.transcriptionSegments ?? 0, resource: this.segments },
    { key: 'frames', label: 'OCR frames', count: () => this.counts()?.ocrFrames ?? 0, resource: this.frames },
    { key: 'fused', label: 'Fused timeline', count: () => this.counts()?.multimodalSegments ?? 0, resource: this.fused },
    { key: 'knowledge', label: 'Knowledge', count: () => this.counts()?.knowledgeUnits ?? 0, resource: this.knowledge },
    { key: 'speakers', label: 'Speakers', count: () => this.counts()?.speakers ?? 0, resource: this.speakers },
  ];

  protected readonly optionalPhases = OPTIONAL_PHASES;
  protected readonly knowledgeTypes = KNOWLEDGE_TYPES;
  protected readonly statusVar = statusVar;
  protected readonly valueOf = valueOf;
  protected readonly timecode = timecode;
  protected readonly humanDuration = humanDuration;
  protected readonly absoluteTime = absoluteTime;

  /**
   * `/videos/{id}/knowledge` has no paging, and a long video can produce hundreds of units. Render
   * a bounded slice and say so, rather than pretending the list is complete or silently truncating.
   * ponytail: a cap, not virtualization — add paging server-side if this becomes the main view.
   */
  protected readonly KNOWLEDGE_CAP = 200;

  /**
   * Sorted by where in the video the unit is, which is not the order the server sends.
   *
   * `KnowledgeUnitRepository.findByVideo_IdOrderByCreatedAtAsc` is *insert* order — one batch of
   * ~40 segments writes its units in whatever order the model emitted them, all sharing a
   * timestamp. Its javadoc calls that "timeline-ordered", which it is not. Rendered down a
   * timecode gutter beside a player, the effect was a list whose rows walk backwards: 00:50,
   * 01:15, 01:50, 00:00, 00:35.
   *
   * Sorted here rather than in the repository because the ordering is this screen's need — the
   * MCP and CLI consumers read the same endpoint and did not ask for it — and `startSeconds` is
   * already on every row. Units without one sort last: they belong to no moment, so there is no
   * honest place for them among the ones that do.
   */
  protected readonly knowledgeAll = computed(() =>
    [...(valueOf(this.knowledge) ?? [])].sort(
      (a, b) => (a.startSeconds ?? Infinity) - (b.startSeconds ?? Infinity),
    ),
  );
  protected readonly knowledgeShown = computed(() => this.knowledgeAll().slice(0, this.KNOWLEDGE_CAP));

  protected readonly video = computed(() => valueOf(this.detail)?.video);
  protected readonly counts = computed(() => valueOf(this.detail)?.counts);
  protected readonly transcription = computed(() => valueOf(this.detail)?.transcription);

  // Media and artifact URLs are bound straight into <video>, <img> and <a>, bypassing the
  // generated client, so they carry the API prefix themselves.
  protected readonly fileUrl = computed(() => `${API_V1}/videos/${this.videoId()}/file`);
  protected readonly txtUrl = computed(() => `${API_V1}/videos/${this.videoId()}/transcription/whisper.txt`);
  protected readonly jsonUrl = computed(() => `${API_V1}/videos/${this.videoId()}/transcription/whisper.json`);

  protected readonly failure = computed(() => this.actionFailure() ?? firstFailure(this.detail));

  /** The panes get a panel of their own: which artifact list failed is a different question. */
  protected readonly paneFailure = computed(() =>
    firstFailure(this.segments, this.frames, this.fused, this.knowledge, this.speakers),
  );

  protected frameUrl(frameId: string | undefined): string {
    return frameId ? `${API_V1}/frames/${frameId}/image` : '';
  }

  /**
   * Relative age against the shared poll clock, the same one every other screen's ages read.
   *
   * `parseServerTime` treats a zoneless timestamp as a server bug rather than assuming UTC, which
   * is the fallback that once hid an hour of skew for a release.
   */
  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected show(pane: Pane): void {
    this.pane.set(pane);
    this.page.set(0);
  }

  /** The player is the spine of this screen: every artifact row is a seek. */
  protected seek(seconds: number | null | undefined): void {
    const el = this.player()?.nativeElement;
    if (!el || seconds === null || seconds === undefined) return;
    el.currentTime = seconds;
    void el.play().catch(() => {
      // Autoplay refusals are the browser's call; the position is already set.
    });
  }

  protected onTime(): void {
    const el = this.player()?.nativeElement;
    if (el) this.cursor.set(el.currentTime);
  }

  protected active(start: number | null | undefined, end: number | null | undefined): boolean {
    if (start === null || start === undefined) return false;
    const now = this.cursor();
    return now >= start && now < (end ?? start + 0.5);
  }

  /**
   * A phase the per-phase rerun endpoint will refuse, so offering it is offering a 409.
   *
   * `VideoPhaseRunnerService` deliberately does *not* consult `applies(ctx)`: the rerun is the
   * operator's escape hatch — "re-OCR after a paddleocr-server upgrade" — so a phase the
   * deployment has switched off still runs, and greying all six of those out would break the one
   * thing this row is for. KNOWLEDGE is the exception, and the only one:
   * `KnowledgeExtractionService` checks `vidingest.knowledge.enabled` itself and throws Conflict,
   * because with the feature off the endpoint would otherwise call the chat model and hang for
   * the full read timeout. `Capabilities` already knows which phases are off, so the answer costs
   * no extra request — it is the same singleton the phase picker reads.
   */
  protected refused(phase: OptionalPhase): boolean {
    return phase === 'KNOWLEDGE' && this.capabilities.disabledOnServer(phase);
  }

  protected chipLabel(phase: OptionalPhase): string {
    if (this.armed() === phase) return `re-run ${phase}?`;
    if (this.running() === phase) return `${phase}…`;
    return phase;
  }

  /**
   * The chip row: two presses.
   *
   * A rerun wipes this video's artifacts for that phase before rebuilding them, so one stray
   * click among seven chips costs a transcript and a ten-minute whisper call. Same arm-then-send
   * shape the videos list already uses for delete. Pressing a different chip re-arms that one, so
   * there is always a way out besides Cancel and Esc.
   *
   * The empty-state CTAs inside the panes call {@link runPhase} directly and stay one press:
   * an empty pane has nothing to destroy.
   */
  protected confirmRun(phase: OptionalPhase): void {
    if (this.armed() !== phase) {
      this.armed.set(phase);
      return;
    }
    this.armed.set(null);
    this.runPhase(phase);
  }

  /**
   * Per-phase rerun is synchronous and idempotent server-side (each phase wipes and repopulates
   * its own artifacts), so the honest feedback is elapsed time and rows written.
   */
  protected runPhase(phase: OptionalPhase): void {
    // Also clears an arm left over from a chip the operator never confirmed, so firing an
    // empty-state CTA cannot leave a different chip sitting one press from wiping its artifacts.
    this.armed.set(null);
    this.running.set(phase);
    this.actionFailure.set(null);
    this.lastRun.set(null);
    this.startTicking();
    this.phases.runVideoPhase(this.videoId(), phase).subscribe({
      next: (result) => {
        this.stopTicking();
        this.running.set(null);
        this.lastRun.set(
          `${result.phase}: ${humanDuration(result.elapsedMs)}${
            result.rowsAffected === null || result.rowsAffected === undefined
              ? ''
              : `, ${result.rowsAffected} row(s)`
          }`,
        );
        this.detail.reload();
        // Wipe-then-repopulate can write fewer rows than last time, and page 3 of the old artifacts
        // is nothing at all in the new ones — an empty pane offering to run the phase that just ran.
        // Both writes land in this turn, so the pane loads once.
        this.page.set(0);
        this.reloadPane();
      },
      error: (err: unknown) => {
        this.stopTicking();
        this.running.set(null);
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }

  private startTicking(): void {
    this.stopTicking();
    const startedAt = Date.now();
    this.elapsedMs.set(0);
    this.ticker = setInterval(() => this.elapsedMs.set(Date.now() - startedAt), 1000);
  }

  private stopTicking(): void {
    clearInterval(this.ticker);
  }

  /**
   * Renames one speaker and writes the server's answer into the row it came from.
   *
   * **Not `speakers.reload()`.** The rows bind `[value]="speaker.displayName || ''"` with no
   * `(input)`, and `track speaker.id` keeps the DOM node across a refetch — so re-fetching the
   * list re-evaluated that binding on *every* row and reset any name the operator had typed but
   * not yet saved. Renaming the first of two speakers silently discarded the second's text.
   * `PATCH /speakers/{id}` answers with the updated `SpeakerDto`, so the one row that changed can
   * be written in place; nothing else is touched, and it is one request rather than two.
   */
  protected rename(speaker: SpeakerDto, displayName: string): void {
    if (!speaker.id) return;
    this.renaming.set(speaker.id);
    this.renameResult.set(null);
    this.actionFailure.set(null);
    this.speakersApi.renameSpeaker(speaker.id, { displayName }).subscribe({
      next: (updated) => {
        this.renaming.set(null);
        if (this.speakers.hasValue()) {
          this.speakers.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
        }
        // A row that silently goes back to looking exactly as it did is indistinguishable from a
        // press that did nothing — the rule the runs board's retry line already follows.
        this.renameResult.set(
          updated.displayName
            ? `Saved: ${updated.label} is now “${updated.displayName}”.`
            : `Cleared the name for ${updated.label}.`,
        );
      },
      error: (err: unknown) => {
        this.renaming.set(null);
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }

  private reloadPane(): void {
    this.panes.find((p) => p.key === this.pane())?.resource.reload();
  }
}

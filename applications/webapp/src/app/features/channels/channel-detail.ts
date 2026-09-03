import { Component, ElementRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import {
  CreatePipelineRunResponse,
  YoutubeChannelVideoSummary,
  YoutubeService,
} from '../../api/generated';
import { blank } from '../../core/domain';
import { acceptedOf, rejectsOf } from '../../core/verdict';
import { absoluteTime, humanAgeCoarse } from '../../core/time';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { firstFailure, valueOf } from '../../core/problem';
import { actionState } from '../../core/action';
import { watchRunFromUrl } from '../../core/watch-run';
import { Capabilities } from '../../core/capabilities';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { RunWatch } from '../../ui/run-watch';
import { Rejects } from '../../ui/rejects';
import { PhasePicker } from '../../ui/phase-picker';
import { syncQueryParams } from '../../core/url-state';
import { clampPage } from '../../core/paging';

/**
 * Whether a sticky element has left the flow and is riding the top edge.
 *
 * Exported and taking only the rect so the trap is executable: the usual way to write this is
 * `entry.intersectionRatio < 1`, and that is wrong on first observe — an element still below the
 * fold has ratio 0 as well, so the shadow appeared on load for any channel whose watch panel had
 * pushed the ingest bar off screen. The top edge answers only the question being asked, and every
 * observer firing carries a fresh rect.
 */
export function pinnedToTop(rect: { top: number }): boolean {
  return rect.top <= 0;
}

const PAGE_SIZE = 50;
const MAX_PER_RUN = 100; // CreatePipelineRunFromYoutubeVideosRequest: @Size(max = 100)

@Component({
  selector: 'vk-channel-detail',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, PhasePicker, RunWatch, Rejects],
  templateUrl: './channel-detail.html',
  styleUrl: './channel-detail.scss',
  // The table's sticky header offset keys off this — see `--ingest-h` in the stylesheet.
  host: { '[class.pinned]': 'stuck()' },
})
export class ChannelDetail {
  readonly channelId = input.required<string>();

  private readonly youtube = inject(YoutubeService);
  protected readonly poller = inject(Poller);
  private readonly capabilities = inject(Capabilities);

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly ingestPanel = viewChild<ElementRef<HTMLElement>>('ingestPanel');

  /**
   * True once the ingest panel has left the flow and is riding the top edge.
   *
   * There is no `:stuck` selector — it is proposed, not shipped — so the panel observes itself
   * against a viewport inset by a pixel, which is what makes it clip the moment it pins and gives
   * the observer something to fire on. No sentinel element and no scroll listener, which on a
   * zoneless app would be a signal write per frame. The answer itself is {@link pinnedToTop}.
   *
   * The same callback publishes the panel's height as `--ingest-h`, because the uploads table's
   * own sticky `th` is also pinned to `top` and would otherwise slide underneath it — the column
   * headers vanishing behind the panel exactly while you scroll the rows you are picking from.
   * `offsetHeight` is read here rather than by a second observer: the intersection geometry changes
   * on resize too, so this fires whenever the height could have moved.
   */
  protected readonly stuck = signal(false);

  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;
  protected readonly onlyNew = signal(true);
  protected readonly picked = signal<Set<string>>(new Set());
  protected readonly skipped = signal<string[]>([]);
  /** Sync and ingest, the two things this screen does to the server. One at a time. */
  protected readonly action = actionState<'sync' | 'ingest'>();
  protected readonly started = signal<CreatePipelineRunResponse | null>(null);
  protected readonly maxPerRun = MAX_PER_RUN;

  constructor() {
    syncQueryParams({ page: this.page, onlyNew: this.onlyNew });
    // Ticking "not ingested only" shrinks the list under the page number that came from the URL.
    clampPage(this.page, PAGE_SIZE, this.videos);

    this.poller.every(
      () => (this.watch.live() ? POLL_LIVE : POLL_IDLE),
      () => {
        if (this.runId()) this.watch.reload();
      },
    );

    // Re-observes when the panel enters the tree, which is only once the channel has loaded.
    effect((onCleanup) => {
      const panel = this.ingestPanel()?.nativeElement;
      if (!panel) return;
      const host = this.host.nativeElement;
      const observer = new IntersectionObserver(
        ([entry]) => {
          this.stuck.set(pinnedToTop(entry.boundingClientRect));
          host.style.setProperty('--ingest-h', `${panel.offsetHeight}px`);
        },
        { threshold: [0, 1], rootMargin: '-1px 0px 0px 0px' },
      );
      observer.observe(panel);
      onCleanup(() => observer.disconnect());
    });
  }

  protected readonly channel = rxResource({
    params: () => ({ id: this.channelId() }),
    stream: ({ params }) => this.youtube.getChannel(params.id),
  });

  /**
   * "not ingested only" is a server filter, not a client one.
   *
   * Filtering the page after it arrived left the pager describing a different set from the rows:
   * ingest thirty and page 0 showed twenty rows under "1-50 of 200", and a mostly-ingested
   * catalog became four pages of near-empty tables. `notIngestedOnly` cuts the rows before the
   * total is counted, so `total()` is the number of rows the operator can actually reach.
   */
  protected readonly videos = rxResource({
    params: () => ({ id: this.channelId(), page: this.page(), onlyNew: this.onlyNew() }),
    stream: ({ params }) =>
      this.youtube.listChannelVideos(
        params.id,
        params.page,
        PAGE_SIZE,
        params.onlyNew || undefined,
      ),
  });

  /**
   * The run this screen just started, advancing in place.
   *
   * Starting a batch used to end the screen: a count and a link, with the work one navigation
   * away — the dead end the ingest screen had already been given lanes to fix. Same helper and
   * the same `vk-run-watch` panel, so the audit tail, the lane build and the `?run=` contract stay
   * fixed in one place. Idle until an ingest answers: `watchRunFromUrl` leaves both its resources
   * alone while the id is empty.
   */
  protected readonly watch = watchRunFromUrl();
  private readonly runId = this.watch.runId;

  protected readonly watched = this.watch.detail;

  /**
   * A 202 does not mean the work was queued. The same body carries REJECTED items with the
   * reason they were turned away ("already running", "was cancelled", duplicate), so the count
   * this screen used to report — `items.length` — was the number *submitted*, not the number
   * accepted. Fifty duplicates read as fifty started runs.
   */
  protected readonly accepted = computed(() => acceptedOf(this.started()));
  protected readonly rejected = computed(() => rejectsOf(this.started()));

  /**
   * Whether there is anything to show — a run this session started, one reopened from the URL, or
   * an ingest that started *nothing*.
   *
   * That last case is why `started()` counts. When every picked video is refused the server creates
   * no run, so `runId` stays empty — and keying the panel on the id alone hid the whole thing,
   * including the `vk-rejects` table naming each refusal and the line saying no run was created.
   * The reasons reached the client and were rendered nowhere.
   */
  protected readonly watching = computed(() => !!this.runId() || !!this.started());

  /**
   * "started · N accepted" is only true of a run this visit began. A run reopened from `?run=` gets
   * no label at all: the panel names the run it is watching, which is the honest thing to say
   * about one this visit did not start.
   */
  protected readonly startedLabel = computed(() => {
    if (!this.started()) return '';
    const rejects = this.rejected().length;
    return `started · ${this.accepted().length} accepted${rejects ? ` · ${rejects} rejected` : ''}`;
  });

  protected readonly rows = computed(() => valueOf(this.videos)?.items ?? []);
  protected readonly total = computed(() => valueOf(this.videos)?.total ?? 0);
  protected readonly newOnPage = computed(() => this.rows().filter((v) => !v.ingested).length);

  /**
   * `publishedAt` is null on every row a `--flat-playlist` discovery produces, so the column was
   * fifty em-dashes wide. Kept for the day a sync carries dates, dropped whenever the page has
   * none rather than hardcoding its absence.
   */
  protected readonly showPublished = computed(() => this.rows().some((v) => !!v.publishedAt));

  /**
   * The sync limit, but only once the catalog has actually hit it — a full catalog is a window
   * `--playlist-end` deep, not the channel's size.
   */
  protected readonly catalogCap = computed(() => {
    const limit = this.capabilities.channelSyncLimit();
    return limit && (valueOf(this.channel)?.videoCount ?? 0) >= limit ? limit : undefined;
  });
  protected readonly pickedCount = computed(() => this.picked().size);
  protected readonly overLimit = computed(() => this.pickedCount() > MAX_PER_RUN);

  protected readonly failure = computed(
    () =>
      this.action.failure() ??
      firstFailure(this.channel, this.videos, this.watch.run, this.watch.audit),
  );

  protected readonly valueOf = valueOf;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

  /** The channel's own last sync, which moves on a half-hour schedule. The run below it does not. */
  protected syncAge(value: string | undefined): string {
    return humanAgeCoarse(value, this.poller.now());
  }


  protected isPicked(video: YoutubeChannelVideoSummary): boolean {
    return !!video.youtubeVideoId && this.picked().has(video.youtubeVideoId);
  }

  protected toggle(video: YoutubeChannelVideoSummary): void {
    const id = video.youtubeVideoId;
    if (!id) return;
    const next = new Set(this.picked());
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.picked.set(next);
  }

  /** Select-all takes the un-ingested videos on this page — the one-click path for new uploads. */
  protected pickAllNew(): void {
    const next = new Set(this.picked());
    for (const video of this.rows()) {
      if (!video.ingested && video.youtubeVideoId) next.add(video.youtubeVideoId);
    }
    this.picked.set(next);
  }

  protected clearPicks(): void {
    this.picked.set(new Set());
  }

  protected sync(): void {
    this.action.start('sync');
    this.youtube.syncChannel(this.channelId()).subscribe({
      next: () => {
        this.action.ok();
        this.channel.reload();
        this.videos.reload();
      },
      error: (err: unknown) => this.action.fail(err),
    });
  }

  protected ingest(): void {
    if (this.pickedCount() === 0 || this.overLimit()) return;
    this.action.start('ingest');
    this.started.set(null);
    this.youtube
      .createRunsFromChannel(this.channelId(), {
        youtubeVideoIds: [...this.picked()],
        skipPhases: this.skipped(),
      })
      .subscribe({
        next: (response) => {
          this.action.ok();
          this.started.set(response);
          this.runId.set(response.runId ?? '');
          // Picks are cleared, not re-seeded from the rejects: the response identifies an item by
          // its watch URL and the selection is keyed by youtubeVideoId, and inventing a parse
          // between the two buys nothing the rejects table below does not already say.
          this.clearPicks();
          this.videos.reload();
        },
        error: (err: unknown) => this.action.fail(err),
      });
  }
}

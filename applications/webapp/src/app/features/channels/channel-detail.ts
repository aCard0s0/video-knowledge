import { Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import {
  CreatePipelineRunResponse,
  RunItem,
  YoutubeChannelVideoSummary,
  YoutubeService,
} from '../../api/generated';
import { blank, statusVar } from '../../core/domain';
import { shortUrl } from '../../core/url';
import { absoluteTime, humanAge } from '../../core/time';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { watchRun } from '../../core/watch-run';
import { Capabilities } from '../../core/capabilities';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { Lane } from '../../ui/lane';
import { Fault } from '../../ui/fault';
import { Rejects } from '../../ui/rejects';
import { PhasePicker } from '../../ui/phase-picker';
import { syncQueryParams } from '../../core/url-state';
import { clampPage } from '../../core/paging';

const PAGE_SIZE = 50;
const MAX_PER_RUN = 100; // CreatePipelineRunFromYoutubeVideosRequest: @Size(max = 100)

@Component({
  selector: 'vk-channel-detail',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, PhasePicker, Lane, Fault, Rejects],
  templateUrl: './channel-detail.html',
  styleUrl: './channel-detail.scss',
})
export class ChannelDetail {
  readonly channelId = input.required<string>();

  private readonly youtube = inject(YoutubeService);
  protected readonly poller = inject(Poller);
  private readonly capabilities = inject(Capabilities);
  private readonly router = inject(Router);

  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;
  protected readonly onlyNew = signal(true);
  protected readonly picked = signal<Set<string>>(new Set());
  protected readonly skipped = signal<string[]>([]);
  protected readonly busy = signal(false);
  private readonly actionFailure = signal<ApiFailure | null>(null);
  protected readonly started = signal<CreatePipelineRunResponse | null>(null);
  protected readonly maxPerRun = MAX_PER_RUN;

  constructor() {
    syncQueryParams({ page: this.page, onlyNew: this.onlyNew });
    // Ticking "not ingested only" shrinks the list under the page number that came from the URL.
    clampPage(this.page, PAGE_SIZE, this.videos);

    this.poller.every(
      () => (this.watch.live() ? POLL_LIVE : POLL_IDLE),
      () => {
        if (this.started()?.runId) this.watch.reload();
      },
    );
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
   * away — the dead end the ingest screen had already been given lanes to fix. Same helper, so
   * the audit tail and the lane build stay fixed in one place for all three screens that draw
   * them. Idle until an ingest answers: `watchRun` leaves both its resources alone while the id
   * is undefined.
   */
  private readonly watch = watchRun(() => this.started()?.runId);

  protected readonly watched = this.watch.detail;
  protected readonly watchedItems = this.watch.items;
  protected readonly lane = this.watch.lane;

  /**
   * A 202 does not mean the work was queued. The same body carries REJECTED items with the
   * reason they were turned away ("already running", "was cancelled", duplicate), so the count
   * this screen used to report — `items.length` — was the number *submitted*, not the number
   * accepted. Fifty duplicates read as fifty started runs.
   */
  protected readonly accepted = computed(
    () => this.started()?.items?.filter((i) => i.status === 'ACCEPTED') ?? [],
  );
  protected readonly rejected = computed(
    () => this.started()?.items?.filter((i) => i.status === 'REJECTED') ?? [],
  );

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
      this.actionFailure() ??
      firstFailure(this.channel, this.videos, this.watch.run, this.watch.audit),
  );

  protected readonly statusVar = statusVar;
  protected readonly valueOf = valueOf;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  /** Same fallback the other two lane screens use: a title if there is one, else the URL with its
   *  boilerplate off — `.truncate` clips the tail, which on a watch URL is the video id. */
  protected label(title: string | undefined, url: string | undefined): string {
    return blank(title) ? shortUrl(url) : title!;
  }

  /** A lane segment opens the run screen with the trail filtered to that phase. */
  protected openPhase(item: RunItem, phase: string): void {
    void this.router.navigate(['/runs', this.started()?.runId], {
      queryParams: { item: item.itemId, phase },
    });
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
    this.busy.set(true);
    this.actionFailure.set(null);
    this.youtube.syncChannel(this.channelId()).subscribe({
      next: () => {
        this.busy.set(false);
        this.channel.reload();
        this.videos.reload();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }

  protected ingest(): void {
    if (this.pickedCount() === 0 || this.overLimit()) return;
    this.busy.set(true);
    this.actionFailure.set(null);
    this.started.set(null);
    this.youtube
      .createRunsFromChannel(this.channelId(), {
        youtubeVideoIds: [...this.picked()],
        skipPhases: this.skipped(),
      })
      .subscribe({
        next: (response) => {
          this.busy.set(false);
          this.started.set(response);
          // Picks are cleared, not re-seeded from the rejects: the response identifies an item by
          // its watch URL and the selection is keyed by youtubeVideoId, and inventing a parse
          // between the two buys nothing the rejects table below does not already say.
          this.clearPicks();
          this.videos.reload();
        },
        error: (err: unknown) => {
          this.busy.set(false);
          this.actionFailure.set(toApiFailure(err));
        },
      });
  }
}

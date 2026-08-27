import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { CreatePipelineRunResponse, YoutubeChannelVideoSummary, YoutubeService } from '../../api/generated';
import { blank, statusVar } from '../../core/domain';
import { absoluteTime, humanAge } from '../../core/time';
import { Poller } from '../../core/poller';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { PhasePicker } from '../../ui/phase-picker';
import { syncQueryParams } from '../../core/url-state';

const PAGE_SIZE = 50;
const MAX_PER_RUN = 100; // CreatePipelineRunFromYoutubeVideosRequest: @Size(max = 100)

@Component({
  selector: 'vk-channel-detail',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, PhasePicker],
  templateUrl: './channel-detail.html',
  styleUrl: './channel-detail.scss',
})
export class ChannelDetail {
  readonly channelId = input.required<string>();

  private readonly youtube = inject(YoutubeService);
  protected readonly poller = inject(Poller);

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
    syncQueryParams({ page: this.page, onlyNew: this.onlyNew }, { onlyNew: true });
  }

  protected readonly channel = rxResource({
    params: () => ({ id: this.channelId() }),
    stream: ({ params }) => this.youtube.getChannel(params.id),
  });

  protected readonly videos = rxResource({
    params: () => ({ id: this.channelId(), page: this.page() }),
    stream: ({ params }) => this.youtube.listChannelVideos(params.id, params.page, PAGE_SIZE),
  });

  protected readonly allRows = computed(() => valueOf(this.videos)?.items ?? []);
  protected readonly rows = computed(() =>
    this.onlyNew() ? this.allRows().filter((v) => !v.ingested) : this.allRows(),
  );
  protected readonly total = computed(() => valueOf(this.videos)?.total ?? 0);
  protected readonly newOnPage = computed(() => this.allRows().filter((v) => !v.ingested).length);
  protected readonly pickedCount = computed(() => this.picked().size);
  protected readonly overLimit = computed(() => this.pickedCount() > MAX_PER_RUN);

  protected readonly failure = computed(
    () => this.actionFailure() ?? firstFailure(this.channel, this.videos),
  );

  protected readonly statusVar = statusVar;
  protected readonly valueOf = valueOf;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
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
    for (const video of this.allRows()) {
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

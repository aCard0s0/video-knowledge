import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { VideoSummary, VideosService } from '../../api/generated';
import { VIDEO_STATUSES, statusVar } from '../../core/domain';
import { absoluteTime, humanAge } from '../../core/time';
import { Poller } from '../../core/poller';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { clampPage } from '../../core/paging';
import { syncQueryParams } from '../../core/url-state';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';

const PAGE_SIZE = 25;

@Component({
  selector: 'vk-videos',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem],
  templateUrl: './videos.html',
  styleUrl: './videos.scss',
})
export class Videos {
  private readonly videos = inject(VideosService);
  protected readonly poller = inject(Poller);

  protected readonly statuses = ['ALL', ...VIDEO_STATUSES];
  protected readonly status = signal('ALL');
  protected readonly channel = signal('');
  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;

  /** Two-step delete: the first click arms the row, the second sends it. */
  protected readonly armed = signal<string | null>(null);
  protected readonly deleting = signal(false);
  private readonly actionFailure = signal<ApiFailure | null>(null);

  constructor() {
    syncQueryParams({ status: this.status, channel: this.channel, page: this.page });
    // Deleting the only row on the last page leaves this page past the end of the list.
    clampPage(this.page, PAGE_SIZE, this.list);
  }

  protected readonly list = rxResource({
    params: () => ({ status: this.status(), channel: this.channel().trim(), page: this.page() }),
    stream: ({ params }) =>
      this.videos.listVideos(
        params.status === 'ALL' ? undefined : params.status,
        undefined,
        params.channel || undefined,
        params.page,
        PAGE_SIZE,
      ),
  });

  protected readonly rows = computed(() => valueOf(this.list)?.items ?? []);
  protected readonly total = computed(() => valueOf(this.list)?.total ?? 0);
  protected readonly failure = computed(() => firstFailure(this.actionFailure, this.list));

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected setStatus(value: string): void {
    this.status.set(value);
    this.page.set(0);
  }

  protected setChannel(value: string): void {
    this.channel.set(value);
    this.page.set(0);
  }

  protected remove(video: VideoSummary): void {
    if (!video.id) return;
    if (this.armed() !== video.id) {
      this.armed.set(video.id);
      return;
    }
    this.deleting.set(true);
    this.actionFailure.set(null);
    this.videos.deleteVideo(video.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.armed.set(null);
        this.list.reload();
      },
      error: (err: unknown) => {
        this.deleting.set(false);
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }
}

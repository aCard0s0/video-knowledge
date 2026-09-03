import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { VideoSummary, VideosService } from '../../api/generated';
import { VIDEO_STATUSES, blank, statusVar } from '../../core/domain';
import { absoluteTime, humanAge } from '../../core/time';
import { POLL_IDLE, POLL_LIVE, Poller } from '../../core/poller';
import { firstFailure, valueOf } from '../../core/problem';
import { actionState } from '../../core/action';
import { clampPage } from '../../core/paging';
import { debouncedWrite } from '../../core/debounce';
import { syncQueryParams } from '../../core/url-state';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { Problem } from '../../ui/problem';

const PAGE_SIZE = 25;

/**
 * Hands focus to the neighbouring row's button before the deleted row is taken away.
 *
 * Keeping focus on the confirm button is what makes the two-press delete work from the keyboard,
 * but the second press removes the element holding it and focus falls to `<body>` — so deleting
 * three videos meant tabbing in from the top of the document three times. Called while the old
 * table is still rendered, so the sibling exists; `@for` tracks by `video.id`, so that sibling's
 * DOM node survives the reload with the focus still on it.
 */
function focusNeighbourRow(): void {
  const row = (document.activeElement as HTMLElement | null)?.closest('tr');
  const neighbour = row?.nextElementSibling ?? row?.previousElementSibling;
  neighbour?.querySelector<HTMLElement>('td.stick button')?.focus();
}

@Component({
  selector: 'vk-videos',
  imports: [RouterLink, StatusBadge, Pager, Empty, Problem, Icon],
  templateUrl: './videos.html',
  styleUrl: './videos.scss',
})
export class Videos {
  private readonly videos = inject(VideosService);
  protected readonly poller = inject(Poller);

  protected readonly statuses = ['ALL', ...VIDEO_STATUSES];
  protected readonly status = signal('ALL');
  protected readonly channel = signal('');
  /** The box filters as it is typed; the write waits for a pause. See `core/debounce.ts`. */
  protected readonly setChannelLive = debouncedWrite((value: string) => this.setChannel(value));
  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;

  /** Two-step delete, keyed by row: which one is armed, which one is in flight, what it said. */
  protected readonly action = actionState();

  constructor() {
    // `status` reaches a `VideoStatus.valueOf` on the server, so an unknown one is a 400 carrying a
    // raw Java enum name — and a chip row has nothing to highlight for it either.
    syncQueryParams(
      { status: this.status, channel: this.channel, page: this.page },
      { status: this.statuses },
    );
    // Deleting the only row on the last page leaves this page past the end of the list.
    clampPage(this.page, PAGE_SIZE, this.list);
    /*
      The list used to fetch once and never again: a row left TRANSCRIBING stayed TRANSCRIBING for
      as long as the screen was open, while the rail underneath said "updated 2.0s ago" and every
      age on the page ticked — the `Poller` was injected here only to move those labels. Six of the
      twenty-five rows on a fresh page 1 were non-terminal and none of them could ever advance.

      Fast only while something on *this page* can still change, which is the same question the
      runs board asks of its own rows. A page of finished videos is a corpus listing, and 15s is
      there to keep it honest with the poll age the rail is already claiming.
    */
    this.poller.every(
      () => (this.moving() ? POLL_LIVE : POLL_IDLE),
      () => {
        this.list.reload();
        // A row that just reached FAILED belongs on the chip whether or not it is on this page.
        this.failedCount.reload();
      },
    );
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

  /**
   * One extra one-row query so the FAILED chip can carry a count — the same trade the runs board
   * makes, for the same reason: that number is why anyone opens this screen, and a `<select>` hid it
   * behind a click.
   */
  protected readonly failedCount = rxResource({
    stream: () => this.videos.listVideos('FAILED', undefined, undefined, 0, 1),
  });
  protected readonly failed = computed(() => valueOf(this.failedCount)?.total ?? 0);

  protected readonly rows = computed(() => valueOf(this.list)?.items ?? []);
  protected readonly total = computed(() => valueOf(this.list)?.total ?? 0);
  /**
   * Something on this page can still change, so the cadence is worth the requests. Stated as *not
   * finished* rather than as a list of what moves, so a `VideoStatus` the server adds counts as
   * moving: polling a settled row costs one request, freezing a live one is the defect this closes.
   */
  private readonly moving = computed(() =>
    this.rows().some((v) => v.status !== 'COMPLETED' && v.status !== 'FAILED'),
  );

  /**
   * The channel column, drawn only when it distinguishes the rows.
   *
   * Both halves matter. Under a channel filter every row repeats the value that is already in the
   * box above — the widest column on the screen saying nothing, which is what the channel catalog
   * settled for `Published` and `State`. But the filter is a *substring* match now, so an active
   * filter no longer implies one channel: `e` matches several. So it also has to actually agree,
   * and an unfiltered page that happens to hold one channel keeps its column rather than dropping it
   * on page 1 and growing it back on page 2.
   */
  protected readonly showChannel = computed(
    () => !this.channel().trim() || new Set(this.rows().map((v) => v.channelName ?? '')).size > 1,
  );

  /** `""` is how this API says absent, so neither half of `source/id` is assumed present. */
  protected sourceLabel(video: VideoSummary): string {
    return [video.source, video.sourceVideoId].filter((part) => !blank(part)).join('/') || '—';
  }
  protected readonly failure = computed(() => this.action.failure() ?? firstFailure(this.list));

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

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

  /** A filter is what emptied the list, so the way out is to clear it, not to go and ingest. */
  protected readonly filtered = computed(() => this.status() !== 'ALL' || !!this.channel().trim());

  protected clearFilters(): void {
    this.status.set('ALL');
    this.channel.set('');
    this.page.set(0);
  }

  /**
   * First press arms the row, second sends it.
   *
   * The repeat-press guard is what lets the button stay enabled while the request is in flight:
   * `disabled` removes an element from the tab order, so disabling the button under the operator's
   * own finger dropped focus to `<body>` — the second time one press did that, the first being the
   * `@if` that used to swap this button for a different one.
   */
  protected remove(video: VideoSummary): void {
    if (!video.id || this.action.isBusy()) return;
    const label = video.title?.trim() || video.sourceVideoId || video.id;
    if (!this.action.confirm(video.id, `Press Confirm delete to remove ${label}.`)) return;

    this.action.start(video.id);
    this.videos.deleteVideo(video.id).subscribe({
      next: () => {
        // The row leaves the table, which on its own looks exactly like a press that did nothing.
        this.action.ok(`Deleted ${label}.`);
        focusNeighbourRow();
        this.list.reload();
        // Deleting a FAILED video is the other way that count changes.
        this.failedCount.reload();
      },
      error: (err: unknown) => this.action.fail(err),
    });
  }
}

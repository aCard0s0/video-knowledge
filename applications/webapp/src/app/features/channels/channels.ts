import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { YoutubeChannelSummary, YoutubeService } from '../../api/generated';
import { blank, statusVar } from '../../core/domain';
import { absoluteTime, humanAge } from '../../core/time';
import { POLL_IDLE, Poller } from '../../core/poller';
import { ApiFailure, toApiFailure } from '../../core/problem';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { syncQueryParams } from '../../core/url-state';

const PAGE_SIZE = 25;

@Component({
  selector: 'vk-channels',
  imports: [ReactiveFormsModule, RouterLink, StatusBadge, Pager, Empty, Problem],
  templateUrl: './channels.html',
  styleUrl: './channels.scss',
})
export class Channels {
  private readonly youtube = inject(YoutubeService);
  protected readonly poller = inject(Poller);

  protected readonly page = signal(0);
  protected readonly size = PAGE_SIZE;
  protected readonly busy = signal<string | null>(null);
  protected readonly failure = signal<ApiFailure | null>(null);

  protected readonly form = new FormGroup({
    url: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    displayName: new FormControl('', { nonNullable: true }),
  });

  protected readonly list = rxResource({
    params: () => ({ page: this.page() }),
    stream: ({ params }) => this.youtube.listChannels(params.page, PAGE_SIZE),
  });

  constructor() {
    syncQueryParams({ page: this.page });
    // The server syncs catalogs on its own every 30 minutes; this just keeps the page honest.
    this.poller.every(
      () => POLL_IDLE,
      () => this.list.reload(),
    );
  }

  protected readonly rows = computed(() => this.list.value()?.items ?? []);
  protected readonly total = computed(() => this.list.value()?.total ?? 0);
  protected readonly listFailure = computed(() => {
    const err = this.list.error();
    return err ? toApiFailure(err) : this.failure();
  });

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  protected add(): void {
    if (this.form.invalid) return;
    const { url, displayName } = this.form.getRawValue();
    this.busy.set('add');
    this.failure.set(null);
    this.youtube.createChannel({ url, displayName: displayName || undefined }).subscribe({
      next: (channel) => {
        this.form.reset();
        this.list.reload();
        // A freshly added channel has an empty catalog until something syncs it, and leaving that
        // to the operator (or to the half-hour scheduler) makes "Add" look like it did nothing.
        if (channel.id) {
          this.sync(channel);
        } else {
          this.busy.set(null);
        }
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.failure.set(toApiFailure(err));
      },
    });
  }

  protected sync(channel: YoutubeChannelSummary): void {
    if (!channel.id) return;
    this.busy.set(channel.id);
    this.failure.set(null);
    this.youtube.syncChannel(channel.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.list.reload();
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.failure.set(toApiFailure(err));
      },
    });
  }
}

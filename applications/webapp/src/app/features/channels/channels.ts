import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';

import { YoutubeChannelSummary, YoutubeService } from '../../api/generated';
import { blank, statusVar } from '../../core/domain';
import { absoluteTime, humanAge } from '../../core/time';
import { POLL_IDLE, Poller } from '../../core/poller';
import { ApiFailure, firstFailure, toApiFailure, valueOf } from '../../core/problem';
import { StatusBadge } from '../../ui/status-badge';
import { Pager } from '../../ui/pager';
import { Empty } from '../../ui/empty';
import { Problem } from '../../ui/problem';
import { syncQueryParams } from '../../core/url-state';
import { clampPage } from '../../core/paging';

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
  private readonly actionFailure = signal<ApiFailure | null>(null);
  /** Errors appear on submit, not while the operator is still typing the first character. */
  private readonly submitted = signal(false);
  private readonly urlField = viewChild<ElementRef<HTMLInputElement>>('urlField');

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
    // Removing the only channel on page 2 leaves this page past the end of the list.
    clampPage(this.page, PAGE_SIZE, this.list);
    // The server syncs catalogs on its own every 30 minutes; this just keeps the page honest.
    this.poller.every(
      () => POLL_IDLE,
      () => this.list.reload(),
    );
  }

  protected readonly rows = computed(() => valueOf(this.list)?.items ?? []);
  protected readonly total = computed(() => valueOf(this.list)?.total ?? 0);
  protected readonly failure = computed(() => this.actionFailure() ?? firstFailure(this.list));

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  /**
   * Reactive forms are not signal-based, so a computed reading `control.invalid` would never
   * recompute — the value has to come through `valueChanges` to be a dependency at all.
   * Shown only once submit has been pressed, not while the first character is being typed.
   */
  private readonly urlValue = toSignal(this.form.controls.url.valueChanges, { initialValue: '' });
  protected readonly urlInvalid = computed(() => this.submitted() && this.urlValue().trim() === '');

  protected add(): void {
    // Submit stays enabled so pressing it explains the problem. Disabled, it just did nothing.
    this.submitted.set(true);
    if (this.form.invalid) {
      this.urlField()?.nativeElement.focus();
      return;
    }
    const { url, displayName } = this.form.getRawValue();
    this.busy.set('add');
    this.actionFailure.set(null);
    this.youtube.createChannel({ url, displayName: displayName || undefined }).subscribe({
      next: (channel) => {
        this.form.reset();
        this.submitted.set(false);
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
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }

  protected sync(channel: YoutubeChannelSummary): void {
    if (!channel.id) return;
    this.busy.set(channel.id);
    this.actionFailure.set(null);
    this.youtube.syncChannel(channel.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.list.reload();
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }

  /**
   * Stop tracking a channel.
   *
   * Without this a mistyped URL was permanent: nothing in the API reached the `DISABLED` status,
   * so the row sat `ERROR` while the server's half-hour sweep re-ran yt-dlp against a dead URL
   * forever. Confirmed first because it drops the discovered catalog — but not the videos already
   * ingested from it, which is what the prompt says.
   */
  protected remove(channel: YoutubeChannelSummary): void {
    if (!channel.id) return;
    const name = channel.displayName || channel.url;
    if (!confirm(`Stop tracking ${name}?\n\nIts discovered catalog goes too. Videos already ingested from it are kept.`)) {
      return;
    }
    this.busy.set(channel.id);
    this.actionFailure.set(null);
    this.youtube.deleteChannel(channel.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.list.reload();
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.actionFailure.set(toApiFailure(err));
      },
    });
  }
}

import { Component, computed, inject, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { RunItem } from '../api/generated';
import { blank, statusVar } from '../core/domain';
import { absoluteTime, humanAge } from '../core/time';
import { Poller } from '../core/poller';
import { shortUrl } from '../core/url';
import { WatchedRun } from '../core/watch-run';
import { Fault } from './fault';
import { Lane } from './lane';
import { StatusBadge } from './status-badge';

/**
 * The run a screen just started, advancing in place.
 *
 * Two screens do this — ingest and channel detail — and both had the whole panel declared inline.
 * `watchRun` had already been pulled out for the four moving parts behind it; the markup was
 * copied instead, and the copy arrived without `?run=`, so the run was lost on every refresh until
 * that was fixed a second time. This is the panel itself, once: head, meta line, and one row per
 * item carrying its lane and its fault.
 *
 * It takes the whole {@link WatchedRun} handle rather than the run, the items and a lane function
 * as three inputs: the handle is one stable reference, and threading its parts through the caller
 * was three aliases per screen that existed only to be passed straight back down.
 *
 * Two slots. `[action]` sits in the meta line, where ingest puts its Retry button — the screens
 * differ on what can be *done* to the run, not on how it is drawn. Everything else projects under
 * the item list, which is where both screens already had their rejects panel.
 */
@Component({
  selector: 'vk-run-watch',
  imports: [RouterLink, StatusBadge, Lane, Fault],
  // Focusable so a finished submit can put the keyboard on the result: the submit button disables
  // itself, and the browser would otherwise drop focus to <body>. The ring stays with the caller,
  // since it is the caller that decides this panel is a landing place (`.watch:focus-visible`).
  host: { class: 'panel watch', tabindex: '-1' },
  template: `
    <div class="panel-head">
      <span class="eyebrow">{{ eyebrow() }}</span>
      <a class="mono sm" [routerLink]="['/runs', watch().runId()]">full run →</a>
    </div>

    @if (watch().detail(); as run) {
      <p class="watch-meta mono sm" aria-live="polite">
        <vk-status [status]="run.status" />
        <span class="muted">· {{ items().length }} item(s) ·
          <span [title]="absoluteTime(run.createdAt)">{{ age(run.createdAt) }}</span></span>
        <ng-content select="[action]" />
      </p>
    }

    <!--
      Between the meta line and the items on purpose: what projects here is a rejects panel, and it
      belongs beside the control that produced it rather than under a list that scrolls at 62vh.
      Outside the run branch, because one of the two things a caller puts here is what it says when
      there is no run — the id was accepted but the batch started nothing.
    -->
    <ng-content />

    @if (watch().detail()) {
      <ul class="watch-items">
        @for (item of items(); track item.itemId) {
          <li>
            <div class="watch-item-head">
              <span class="spine-dot" [style.background]="statusVar(item.status)" aria-hidden="true"></span>
              <!-- The whole URL in the title: the .url column clips its tail, and on a watch URL
                   that tail is the video id — the only thing telling two rows of a batch apart.
                   No backticks in here: this comment sits inside a template literal. -->
              <span class="url" [title]="item.url || label(item)">
                @if (channelLink() && !blank(item.channelName)) {
                  <a class="channel" routerLink="/videos" [queryParams]="{ channel: item.channelName }">{{ item.channelName }}</a> -
                }
                {{ label(item) }}
              </span>
              <vk-status [status]="item.status" />
            </div>
            <!--
              The status input is what tells the lane an item stopped before phase one: without it
              the lane cannot distinguish "reaped while queued" from "finished", and announced a
              FAILED item as complete.

              A segment opens the run screen with the trail filtered to that phase. Unwired, these
              are focusable buttons that do nothing — ten per completed item.
            -->
            <vk-lane [segments]="watch().lane(item)" [status]="item.status" (pick)="openPhase(item, $event)" />
            <vk-fault [errorCode]="item.errorCode" [error]="item.error" />
          </li>
        }
      </ul>
    }
  `,
})
export class RunWatch {
  readonly watch = input.required<WatchedRun>();
  /**
   * The half of the head only the caller knows: "started · 2 accepted" is true of a run this visit
   * began and false of one reopened from a link. Left empty, the panel says which run it is
   * watching — the honest answer both for a reopened run and for the seconds before one loads.
   */
  readonly headline = input('');
  /**
   * Whether an item's channel links to the rest of its videos. On ingest a batch spans channels and
   * the link answers "what else came from here"; on a channel screen every row would repeat the
   * channel the operator is already standing on.
   */
  readonly channelLink = input(false);

  private readonly router = inject(Router);
  private readonly poller = inject(Poller);

  protected readonly items = computed(() => this.watch().items());

  protected readonly eyebrow = computed(
    () => this.headline() || `watching · ${this.watch().runId().slice(0, 8)}`,
  );

  protected readonly statusVar = statusVar;
  protected readonly absoluteTime = absoluteTime;
  protected readonly blank = blank;

  protected age(value: string | undefined): string {
    return humanAge(value, this.poller.now());
  }

  /** Title first, then the URL with its boilerplate off — never the raw URL, which clips its id. */
  protected label(item: RunItem): string {
    return blank(item.videoTitle) ? shortUrl(item.url) : item.videoTitle!;
  }

  protected openPhase(item: RunItem, phase: string): void {
    void this.router.navigate(['/runs', this.watch().runId()], {
      queryParams: { item: item.itemId, phase },
    });
  }
}

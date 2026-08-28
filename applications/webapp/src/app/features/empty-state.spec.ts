import { Type } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuditService, PipelinesService, VideosService, YoutubeService } from '../api/generated';
import { Audit } from './audit/audit';
import { Channels } from './channels/channels';
import { Runs } from './runs/runs';
import { Videos } from './videos/videos';

/**
 * Every paged screen shares one shape: `rows()` is `valueOf(list)?.items ?? []`, and `valueOf`
 * hands back `undefined` while the resource is loading *and* while it has errored. So the empty
 * state used to render over a request that never answered — "No videos match this filter." sitting
 * under a problem panel saying the server was unreachable.
 *
 * Asserted across all of them rather than on one, because the edit is identical everywhere and one
 * screen passing says nothing about the next. The runs board was the one that shipped without it:
 * a dead `/pipelines` answered with "No runs match this filter." over a "Start an ingest →" link.
 */
const PAGE = { items: [], page: 0, size: 25, total: 0 };

/**
 * The message each screen shows for an empty response with **nothing filtered**. Videos separates
 * the two emptinesses (see the last test in this file); the others do not, so for them this is the
 * only message there is.
 */
const SCREENS: { name: string; component: Type<unknown>; service: unknown; method: string; empty: string }[] = [
  { name: 'Videos', component: Videos, service: VideosService, method: 'listVideos', empty: 'No videos ingested yet' },
  { name: 'Audit', component: Audit, service: AuditService, method: 'listEvents', empty: 'No events match' },
  { name: 'Channels', component: Channels, service: YoutubeService, method: 'listChannels', empty: 'No channels tracked' },
  { name: 'Runs', component: Runs, service: PipelinesService, method: 'listRuns', empty: 'No runs match' },
];

function screen(s: (typeof SCREENS)[number], stream: () => unknown) {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: s.service, useValue: { [s.method]: stream } }],
  });
  const fixture = TestBed.createComponent(s.component);
  TestBed.tick();
  return fixture.nativeElement as HTMLElement;
}

describe('list screens: the empty state is not a failed load', () => {
  beforeEach(() => TestBed.resetTestingModule());

  for (const s of SCREENS) {
    it(`${s.name} says "nothing matches" only when the server said so`, () => {
      const el = screen(s, () => of(PAGE));
      expect(el.querySelector('vk-empty')!.textContent).toContain(s.empty);
    });

    it(`${s.name} does not claim an empty list when the request failed`, () => {
      const el = screen(s, () => throwError(() => new HttpErrorResponse({ status: 0 })));

      expect(el.querySelector('vk-empty')).toBeNull();
      // The panel above is what answers — and it is never a bare "something went wrong".
      expect(el.querySelector('vk-problem')!.textContent).toContain('Server unreachable');
    });
  }

  /**
   * Which emptiness it is decides the way out. The channel filter is a search box, so a mistyped
   * fragment is the likeliest way to empty this screen — and the answer to that is to clear it, not
   * the "Ingest a URL →" link that was offered unconditionally. Same shape as the channel catalog
   * reporting "Every upload is already ingested" over a sync that had 404ed.
   */
  it('Videos offers to clear the filter that emptied it, not an ingest link', () => {
    const el = screen(SCREENS[0], () => of(PAGE));
    expect(el.querySelector('vk-empty')!.textContent).toContain('No videos ingested yet');

    // The box filters as it is typed, one pause at a time — `input`, then the pause.
    vi.useFakeTimers();
    const input = el.querySelector<HTMLInputElement>('#channel')!;
    input.value = 'comp';
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(250);
    vi.useRealTimers();
    TestBed.tick();

    const empty = el.querySelector('vk-empty')!;
    expect(empty.textContent).toContain('No videos match');
    expect(empty.querySelector('button')!.textContent).toContain('Clear filters');
    expect(empty.querySelector('a')).toBeNull();
  });
});

import { Type } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AuditService, VideosService, YoutubeService } from '../api/generated';
import { Audit } from './audit/audit';
import { Channels } from './channels/channels';
import { Videos } from './videos/videos';

/**
 * The three paged list screens share one shape: `rows()` is `valueOf(list)?.items ?? []`, and
 * `valueOf` hands back `undefined` while the resource is loading *and* while it has errored. So the
 * empty state used to render over a request that never answered — "No videos match this filter."
 * sitting under a problem panel saying the server was unreachable.
 *
 * Asserted across all three rather than on one of them, because the edit is identical in three
 * places and one screen passing says nothing about the other two.
 */
const PAGE = { items: [], page: 0, size: 25, total: 0 };

const SCREENS: { name: string; component: Type<unknown>; service: unknown; method: string; empty: string }[] = [
  { name: 'Videos', component: Videos, service: VideosService, method: 'listVideos', empty: 'No videos match' },
  { name: 'Audit', component: Audit, service: AuditService, method: 'listEvents', empty: 'No events match' },
  { name: 'Channels', component: Channels, service: YoutubeService, method: 'listChannels', empty: 'No channels tracked' },
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
});

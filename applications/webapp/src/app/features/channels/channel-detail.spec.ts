import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, RouterOutlet, provideRouter, withComponentInputBinding } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ChannelDetail, pinnedToTop } from './channel-detail';
import { ItemResultStatusEnum, PipelinesService, YoutubeService } from '../../api/generated';

/**
 * The ingest bar is `position: sticky; top: 0` and paints a shadow once it pins. There is no
 * `:stuck` selector, so an IntersectionObserver answers it — and the shape that answer takes is
 * the whole point of these four cases.
 */
describe('pinnedToTop', () => {
  it('is pinned once the top edge reaches the viewport top', () => {
    expect(pinnedToTop({ top: 0 })).toBe(true);
  });

  it('stays pinned while sticky holds it against the edge', () => {
    // Sticky clamps at 0, but a sub-pixel layout can report a hair under it.
    expect(pinnedToTop({ top: -0.5 })).toBe(true);
  });

  it('is not pinned while it still sits in the flow', () => {
    expect(pinnedToTop({ top: 240 })).toBe(false);
  });

  /**
   * The regression this replaced `intersectionRatio < 1` for. A panel below the fold is not
   * intersecting at all, so the ratio reads 0 — indistinguishable from pinned-and-clipped. On any
   * channel whose watch panel pushed the bar off screen, the shadow was painted on load.
   */
  it('is not pinned when it is below the fold, where the ratio also reads 0', () => {
    expect(pinnedToTop({ top: 1400 })).toBe(false);
  });
});

const CHANNEL_ID = '9c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f';
const RUN_ID = '7f3c1a2b-0000-4000-8000-000000000000';

const CHANNEL = {
  id: CHANNEL_ID,
  displayName: 'Rick TV',
  url: 'https://www.youtube.com/@ricktv',
  status: 'READY',
  videoCount: 2,
};

const UPLOAD = (n: number) => ({
  id: `u${n}`,
  youtubeVideoId: `vid${n}`,
  title: `Upload ${n}`,
  watchUrl: `https://www.youtube.com/watch?v=vid${n}`,
  ingested: false,
  publishedAt: null,
});

/**
 * Routed, not bare: `channelId` arrives through `withComponentInputBinding`, and `page`/`onlyNew`
 * and `?run=` all arrive through `syncQueryParams`, which reads the real `ActivatedRoute` snapshot.
 */
@Component({ selector: 'vk-test-host', imports: [RouterOutlet], template: '<router-outlet />' })
class Host {}

async function screen(query = '', createRuns: unknown = null) {
  // The sticky ingest bar observes itself, and the test DOM has no IntersectionObserver — without
  // this the effect throws during change detection and takes the whole render with it. What it
  // answers is `pinnedToTop`, unit-tested above.
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      observe() {}
      disconnect() {}
    },
  );

  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [{ path: 'channels/:channelId', component: ChannelDetail }],
        withComponentInputBinding(),
      ),
      provideLocationMocks(),
      {
        provide: YoutubeService,
        useValue: {
          getChannel: () => of(CHANNEL),
          // Long enough for page 1 to exist: against a 2-row total `clampPage` correctly pulls
          // `?page=1` back to 0, which is a different rule with its own tests.
          listChannelVideos: () =>
            of({ items: [UPLOAD(1), UPLOAD(2)], page: 0, size: 50, total: 120 }),
          syncChannel: () => of(CHANNEL),
          createRunsFromChannel: () => of(createRuns),
        },
      },
      {
        provide: PipelinesService,
        useValue: {
          getPipelineCapabilities: () => of({ enabledPhases: [], channelSyncLimit: 50 }),
          getRun: () => of({ id: RUN_ID, status: 'PENDING', items: [] }),
          auditRun: () => of({ items: [], page: 0, size: 500, total: 0 }),
        },
      },
    ],
  });

  const fixture = TestBed.createComponent(Host);
  const router = TestBed.inject(Router);
  await router.navigateByUrl(`/channels/${CHANNEL_ID}${query}`);
  TestBed.tick();
  return { router, el: fixture.nativeElement as HTMLElement };
}

/** Effects navigate, and a navigation resolves a microtask later. */
function settle() {
  TestBed.tick();
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('channel detail: two query-param owners on one screen', () => {
  beforeEach(() => TestBed.resetTestingModule());

  /**
   * This screen runs **two** `syncQueryParams`: its own for `page`/`onlyNew`, and the one inside
   * `watchRunFromUrl` for `?run=`. Both write through `router.navigate` with
   * `queryParamsHandling: 'merge'`, so each may only add and remove its own keys — a write that
   * replaced the query would drop the other owner's state on the floor.
   */
  it('keeps each owner’s params through the other’s write', async () => {
    const { router } = await screen(`?page=1&run=${RUN_ID}`);
    await settle();

    expect(router.url).toBe(`/channels/${CHANNEL_ID}?page=1&run=${RUN_ID}`);
  });

  /** A default stays out of the URL, and that must not take the other owner's key with it. */
  it('drops only its own defaults', async () => {
    const { router } = await screen(`?page=0&run=${RUN_ID}`);
    await settle();

    expect(router.url).toBe(`/channels/${CHANNEL_ID}?run=${RUN_ID}`);
  });
});

describe('channel detail: an ingest that starts nothing', () => {
  beforeEach(() => TestBed.resetTestingModule());

  /**
   * When every picked video is refused the server creates no run, so `runId` stays empty — and
   * keying the panel on the id alone hid the entire thing, including the table naming each refusal.
   * The reasons reached the client and were rendered nowhere.
   */
  it('shows the reasons rather than nothing at all', async () => {
    const { el } = await screen('', {
      runId: null,
      items: [
        {
          url: 'https://www.youtube.com/watch?v=vid1',
          status: ItemResultStatusEnum.Rejected,
          reason: 'duplicate video',
        },
      ],
    });

    el.querySelectorAll<HTMLInputElement>('td.pick input')[0].click();
    TestBed.tick();
    el.querySelector<HTMLButtonElement>('.ingest .btn-primary')!.click();
    TestBed.tick();

    const panel = el.querySelector('vk-run-watch')!;
    expect(panel.textContent).toContain('duplicate video');
    expect(panel.textContent).toContain('Nothing was accepted');
    // No run means no run to open, so the panel must not offer a link to one.
    expect(panel.querySelector('.panel-head a')).toBeNull();
  });
});

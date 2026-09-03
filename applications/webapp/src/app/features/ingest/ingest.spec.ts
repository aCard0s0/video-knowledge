import { Component } from '@angular/core';
import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, RouterOutlet, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { Ingest, parseUrls } from './ingest';
import { ItemResultStatusEnum, PipelinesService } from '../../api/generated';
import { OPTIONAL_PHASES } from '../../core/domain';

describe('parseUrls', () => {
  it('counts lines, not tokens — three words on one line were reported as three lines', () => {
    expect(parseUrls('some junk text').invalid).toEqual(['some junk text']);
  });

  it('takes several URLs off one line, because a paste is not always one per line', () => {
    expect(parseUrls('https://a, https://b').valid).toEqual(['https://a', 'https://b']);
  });

  it('leaves a mixed line out whole rather than ingesting half of it', () => {
    const parsed = parseUrls('https://a junk');
    expect(parsed.valid).toEqual([]);
    expect(parsed.invalid).toEqual(['https://a junk']);
  });

  it('drops repeats and says how many', () => {
    const parsed = parseUrls('https://a\nhttps://a\nhttps://b');
    expect(parsed.valid).toEqual(['https://a', 'https://b']);
    expect(parsed.duplicates).toBe(1);
  });

  it('ignores blank lines and surrounding whitespace', () => {
    expect(parseUrls('\n  https://a  \n\n').valid).toEqual(['https://a']);
  });
});

/**
 * The screen driven through its own controls — typing in the textarea and pressing the buttons —
 * rather than by calling the handlers. A handler that is never wired to its button passes every
 * check that calls it directly, which is exactly what the missing `[status]` binding did.
 *
 * Reached through the router rather than created directly, because one of the four things under
 * test *is* the URL: `syncQueryParams` writes relative to the active route, so a component built
 * outside one writes its query params onto `/` and the check passes on a screen nobody could reach.
 */
@Component({ selector: 'vk-test-host', imports: [RouterOutlet], template: '<router-outlet />' })
class Host {}

@Component({ selector: 'vk-test-stub', template: 'stub' })
class Stub {}

const FAILED_RUN = {
  id: 'run-1',
  status: 'FAILED',
  phase: 'DONE',
  createdAt: '2026-08-27T15:43:56.094678',
  items: [
    {
      itemId: 'item-1',
      url: 'https://www.youtube.com/watch?v=xxxxxxxxxxx',
      status: 'FAILED',
      // The reconciler's marker, not a phase: nothing on the lane can carry the outcome.
      failedPhase: 'CREATED',
      errorCode: 'UNEXPECTED',
      error: 'reconciler: stuck PENDING in phase CREATED since …',
      phaseUpdatedAt: '2026-08-27T15:43:57.545643',
    },
  ],
};

/** Every call the screen makes, and a record of what it sent. */
function stubPipelines(overrides: Record<string, unknown> = {}) {
  const calls: { createRuns: unknown[]; retryRun: unknown[] } = { createRuns: [], retryRun: [] };
  const service = {
    calls,
    getPipelineCapabilities: () =>
      of({ enabledPhases: [...OPTIONAL_PHASES], channelSyncLimit: 50 }),
    listRuns: () => of({ items: [], page: 0, size: 5, total: 0 }),
    getRun: () => of(FAILED_RUN),
    auditRun: () => of({ items: [], page: 0, size: 500, total: 0 }),
    createRuns: (request: unknown) => {
      calls.createRuns.push(request);
      return of({
        runId: 'run-1',
        items: [
          {
            url: 'https://www.youtube.com/watch?v=xxxxxxxxxxx',
            status: ItemResultStatusEnum.Accepted,
          },
        ],
      });
    },
    retryRun: (...args: unknown[]) => {
      calls.retryRun.push(args);
      return of({
        runId: 'run-1',
        items: [{ url: 'https://a', status: ItemResultStatusEnum.Accepted }],
      });
    },
    ...overrides,
  };
  return service;
}

async function screen(pipelines: ReturnType<typeof stubPipelines>) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'ingest', component: Ingest },
        { path: 'runs/:runId', component: Stub },
      ]),
      provideLocationMocks(),
      { provide: PipelinesService, useValue: pipelines },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  const router = TestBed.inject(Router);
  await router.navigateByUrl('/ingest');
  TestBed.tick();
  return { fixture, router, el: fixture.nativeElement as HTMLElement };
}

function type(el: HTMLElement, value: string) {
  const textarea = el.querySelector('textarea')!;
  textarea.value = value;
  textarea.dispatchEvent(new Event('input'));
  TestBed.tick();
  return textarea;
}

/** Effects run on tick; the navigation one of them starts resolves a microtask later. */
function settle() {
  TestBed.tick();
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function press(el: HTMLElement, selector: string) {
  el.querySelector<HTMLButtonElement>(selector)!.click();
  TestBed.tick();
}

describe('Ingest', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('keeps the lines it never sent, instead of deleting the operator’s typos', async () => {
    const pipelines = stubPipelines();
    const { el } = await screen(pipelines);

    const textarea = type(
      el,
      ['https://www.youtube.com/watch?v=xxxxxxxxxxx', 'www.youtube.com/watch?v=TYPO'].join('\n'),
    );
    press(el, 'button[type=submit]');

    // Only the http(s) line was ever sent…
    expect(pipelines.calls.createRuns).toEqual([
      { urls: ['https://www.youtube.com/watch?v=xxxxxxxxxxx'], skipPhases: [] },
    ]);
    // …and the one that was not is still there to be fixed, with the reason beside it.
    expect(textarea.value).toBe('www.youtube.com/watch?v=TYPO');
    expect(el.querySelector('.rejects')?.textContent).toContain('not http(s)');
  });

  it('puts the started run in the URL, so a refresh does not lose it', async () => {
    const pipelines = stubPipelines();
    const { el, router } = await screen(pipelines);

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');
    await settle(); // syncQueryParams navigates from an effect, and navigation is a promise

    expect(router.url).toBe('/ingest?run=run-1');
  });

  it('tells the lane the item’s status, so a run marker is not drawn as a finish', async () => {
    const { el } = await screen(stubPipelines());

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');

    // `failedPhase: CREATED` leaves every segment 'pending'. Without [status] the lane has no way
    // to know the item is over and announces it as complete.
    expect(el.querySelector('.lane')?.getAttribute('aria-label')).toBe(
      'Phase timeline, failed before any phase ran',
    );
  });

  it('retries without a body, so the run keeps the phases it was created with', async () => {
    const pipelines = stubPipelines();
    const { el } = await screen(pipelines);

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');
    // The phase picker describes the *next* run — it must not be sent as this one's skipPhases.
    el.querySelectorAll<HTMLInputElement>('vk-phase-picker input')[0].click();
    TestBed.tick();
    press(el, 'button.retry');

    expect(pipelines.calls.retryRun).toEqual([['run-1']]);
  });

  it('reads the rejects out of a 202 rather than trusting the envelope', async () => {
    const pipelines = stubPipelines({
      retryRun: () =>
        of({
          runId: 'run-1',
          items: [
            {
              url: 'https://a',
              status: ItemResultStatusEnum.Rejected,
              reason: 'run item was cancelled',
            },
          ],
        }),
    });
    const { el } = await screen(pipelines);

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');
    press(el, 'button.retry');

    expect(el.querySelector('vk-rejects')!.textContent).toContain('run item was cancelled');
  });

  it('shows a declined retry in full, and never as a bare failure', async () => {
    const pipelines = stubPipelines({
      retryRun: () =>
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              statusText: 'Conflict',
              error: {
                status: 409,
                title: 'Conflict',
                detail: 'Only a FAILED run may be retried.',
              },
              headers: new HttpHeaders({ 'X-Correlation-Id': 'corr-42' }),
            }),
        ),
    });
    const { el } = await screen(pipelines);

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');
    press(el, 'button.retry');

    const panel = el.querySelector('vk-problem')!.textContent!;
    expect(panel).toContain('Only a FAILED run may be retried.');
  });
  it('warns about the lines it will leave out rather than counting them at the same weight', async () => {
    const { el } = await screen(stubPipelines());

    type(
      el,
      ['https://www.youtube.com/watch?v=xxxxxxxxxxx', 'www.youtube.com/watch?v=TYPO'].join('\n'),
    );

    expect(el.querySelector('.counts')!.textContent).toContain('1 line(s) are not http(s)');
    expect(el.querySelector('.counts')!.classList.contains('warn')).toBe(true);
    // On the field, not in a live region: this changes on every keystroke.
    expect(el.querySelector('#urls')!.getAttribute('aria-describedby')).toBe('url-counts');
  });

  it('says which phases this deployment will not run, instead of ticking them', async () => {
    const { el } = await screen(
      stubPipelines({
        getPipelineCapabilities: () =>
          of({ enabledPhases: ['TRANSCRIBE', 'FUSE', 'CONTEXT'], channelSyncLimit: 50 }),
      }),
    );

    const unavailable = [...el.querySelectorAll('label.chip.unavailable')].map((c) =>
      c.textContent?.trim(),
    );
    expect(unavailable).toEqual(['DIARIZE', 'FRAME_SAMPLE', 'OCR', 'KNOWLEDGE']);
  });

  it('does not report an unanswered list as an empty one', async () => {
    const { el } = await screen(
      stubPipelines({ listRuns: () => throwError(() => new HttpErrorResponse({ status: 500 })) }),
    );

    // Nothing at all, not a "Loading…" that never resolves: the panel above is the answer.
    expect(el.querySelector('.quiet')).toBeNull();
    expect(el.querySelector('vk-problem')!.textContent).toContain('500');
  });

  it('reads the per-URL reasons out of the 400 that carries them, not as a bare HTTP fault', async () => {
    // POST /pipelines answers 400 with a CreatePipelineRunResponse — not a ProblemDetail — when
    // every URL was rejected.
    const { el } = await screen(
      stubPipelines({
        createRuns: () =>
          throwError(
            () =>
              new HttpErrorResponse({
                status: 400,
                error: {
                  runId: null,
                  items: [
                    {
                      url: 'https://a',
                      status: ItemResultStatusEnum.Rejected,
                      reason: 'duplicate url in request',
                    },
                  ],
                },
              }),
          ),
      }),
    );

    type(el, 'https://a');
    press(el, 'button[type=submit]');

    expect(el.querySelector('.rejects')!.textContent).toContain('duplicate url in request');
    expect(el.querySelector('vk-problem')!.textContent!.trim()).toBe('');
  });

  it('gives the recent list a status in words, not only a coloured bar', async () => {
    const pipelines = stubPipelines({
      listRuns: () =>
        of({
          items: [
            {
              id: 'run-9',
              status: 'FAILED',
              errorCode: 'UPSTREAM_TOOL_FAILURE',
              error: 'yt-dlp failed',
              videoUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
              videoTitle: '',
              channelName: 'Rick TV',
              videoId: 'vid-9',
              // The list opens on `today`, so a fixture with a fixed date would rot into a run the
              // default range filters out — which is the range test below, not this one.
              createdAt: new Date().toISOString(),
            },
          ],
          page: 0,
          size: 200,
          total: 1,
        }),
    });
    const { el } = await screen(pipelines);

    const row = el.querySelector('.recent li')!;
    expect(row.querySelector('vk-status')!.textContent).toContain('FAILED');
    // The code rides the row and is the control that opens the message under it. Nothing else in
    // the row toggles: a click anywhere else opens the run.
    const code = row.querySelector<HTMLButtonElement>('vk-error-code button')!;
    expect(code.textContent).toContain('UPSTREAM_TOOL_FAILURE');
    expect(row.querySelector('.msg')).toBeNull();

    code.click();
    TestBed.tick();
    expect(row.querySelector('.msg')!.textContent).toContain('yt-dlp failed');
    // A column each, and a different destination each: the channel to the rest of its videos, the
    // title to the video itself.
    const channel = row.querySelector('.channel a')! as HTMLAnchorElement;
    expect(channel.textContent).toContain('Rick TV');
    expect(channel.getAttribute('href')).toBe('/videos?channel=Rick%20TV');
    const title = row.querySelector('.url a')! as HTMLAnchorElement;
    expect(title.getAttribute('href')).toBe('/videos/vid-9');
    // …and the label keeps the video id a 34ch tail-clip was eating.
    expect(row.querySelector('.url')!.textContent).toContain('dQw4w9WgXcQ');
  });

  /**
   * The range is the server's, so what the chips have to get right is the bound they *send*. It is
   * local midnight and it carries an offset: the client is the only side that knows which midnight
   * "today" means, and cutting the page client-side was a range only while every run fit in it.
   */
  it('asks the server for the range, from local midnight, and for nothing when it is `all`', async () => {
    const sent: (string | undefined)[] = [];
    const pipelines = stubPipelines({
      listRuns: (
        _status?: string,
        _page?: number,
        _size?: number,
        _ids?: string[],
        _live?: boolean,
        _sortBy?: string,
        createdAfter?: string,
      ) => {
        sent.push(createdAfter);
        return of({ items: [], page: 0, size: 50, total: 0 });
      },
    });
    const { el } = await screen(pipelines);

    const midnight = new Date();
    midnight.setHours(0, 0, 0, 0);
    expect(sent.at(-1)).toBe(midnight.toISOString());

    const chip = (label: string) =>
      [...el.querySelectorAll<HTMLButtonElement>('.chips .chip')].find(
        (b) => b.textContent!.trim() === label,
      )!;

    chip('week').click();
    TestBed.tick();
    const weekAgo = new Date(midnight);
    weekAgo.setDate(weekAgo.getDate() - 6);
    expect(sent.at(-1)).toBe(weekAgo.toISOString());

    // `all` is the absence of the bound, not a bound far in the past — the server drops the
    // predicate, and a date chosen here would be a floor nobody asked for.
    chip('all').click();
    TestBed.tick();
    expect(sent.at(-1)).toBeUndefined();
  });

  it('sends a lane segment to the run screen with the trail filtered to that phase', async () => {
    // A lane needs a segment that actually ran: the CREATED case collapses to one disabled void.
    const { el, router } = await screen(
      stubPipelines({
        getRun: () =>
          of({
            ...FAILED_RUN,
            items: [{ ...FAILED_RUN.items[0], failedPhase: 'METADATA' }],
          }),
        auditRun: () =>
          of({
            items: [
              {
                itemId: 'item-1',
                attempt: 1,
                eventType: 'ITEM_PHASE_ENTERED',
                phase: 'METADATA',
                occurredAt: '2026-08-27T15:43:56.094678Z',
              },
            ],
            page: 0,
            size: 500,
            total: 1,
          }),
      }),
    );

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');
    // The only enabled segment: the merged void is not clickable.
    press(el, 'vk-lane .lane button:not([disabled])');
    await settle();

    expect(router.url).toBe('/runs/run-1?item=item-1&phase=METADATA');
  });
});

import { Component } from '@angular/core';
import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, RouterOutlet, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { Ingest, rejectsOf } from './ingest';
import { ItemResultStatusEnum, PipelinesService } from '../../api/generated';

describe('rejectsOf', () => {
  it('keeps only the declined items, and gives a blank reason something to say', () => {
    expect(
      rejectsOf({
        runId: 'r1',
        items: [
          { url: 'https://a', status: ItemResultStatusEnum.Accepted },
          { url: 'https://b', status: ItemResultStatusEnum.Rejected, reason: 'run item is already running' },
          { url: 'https://c', status: ItemResultStatusEnum.Rejected },
        ],
      }),
    ).toEqual([
      { url: 'https://b', reason: 'run item is already running' },
      { url: 'https://c', reason: 'rejected' },
    ]);
  });

  it('has nothing to say before a request has answered', () => {
    expect(rejectsOf(null)).toEqual([]);
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
    listRuns: () => of({ items: [], page: 0, size: 5, total: 0 }),
    getRun: () => of(FAILED_RUN),
    auditRun: () => of({ items: [], page: 0, size: 500, total: 0 }),
    createRuns: (request: unknown) => {
      calls.createRuns.push(request);
      return of({ runId: 'run-1', items: [{ url: 'https://www.youtube.com/watch?v=xxxxxxxxxxx', status: ItemResultStatusEnum.Accepted }] });
    },
    retryRun: (...args: unknown[]) => {
      calls.retryRun.push(args);
      return of({ runId: 'run-1', items: [{ url: 'https://a', status: ItemResultStatusEnum.Accepted }] });
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
        of({ runId: 'run-1', items: [{ url: 'https://a', status: ItemResultStatusEnum.Rejected, reason: 'run item was cancelled' }] }),
    });
    const { el } = await screen(pipelines);

    type(el, 'https://www.youtube.com/watch?v=xxxxxxxxxxx');
    press(el, 'button[type=submit]');
    press(el, 'button.retry');

    expect(el.querySelector('.retry-rejects')?.textContent).toContain('run item was cancelled');
  });

  it('shows a declined retry in full, and never as a bare failure', async () => {
    const pipelines = stubPipelines({
      retryRun: () =>
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              statusText: 'Conflict',
              error: { status: 409, title: 'Conflict', detail: 'Only a FAILED run may be retried.' },
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
});

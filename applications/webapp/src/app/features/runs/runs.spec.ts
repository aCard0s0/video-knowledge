import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { Runs, hasFault, marker, retrySaid } from './runs';
import { PipelinesService, RunSummary } from '../../api/generated';

/** The API spells absent as "", never null — every string field on a summary comes back set. */
const run = (o: Partial<RunSummary>): RunSummary => ({
  id: '710a9419-3f2b-4d1e-9a77-0c1d5e8b2a44',
  status: 'COMPLETED',
  phase: 'DONE',
  errorCode: '',
  error: '',
  videoUrl: '',
  videoId: '',
  channelName: '',
  videoTitle: '',
  videoCount: 0,
  createdAt: '2026-08-26T14:41:02.100000',
  updatedAt: '2026-08-26T14:41:09.400000',
  ...o,
});

describe('marker', () => {
  it('names the phase a run is actually in', () => {
    expect(marker(run({ status: 'IN_PROGRESS', phase: 'TRANSCRIBE' }))).toBe('TRANSCRIBE');
  });

  it('does not render CREATED as a step — a run wears it until METADATA starts', () => {
    expect(marker(run({ status: 'PENDING', phase: 'CREATED' }))).toBe('queued');
  });

  it('never repeats the DONE a FAILED run reports as its phase', () => {
    expect(marker(run({ status: 'FAILED', phase: 'DONE' }))).toBe('—');
  });

});

describe('hasFault', () => {
  it('shows a message that arrived without a code', () => {
    expect(hasFault(run({ status: 'FAILED', errorCode: '', error: 'no message recorded upstream' }))).toBe(true);
  });

  it('shows a code that arrived without a message', () => {
    expect(hasFault(run({ status: 'CANCELLED', errorCode: 'DUPLICATE_VIDEO', error: '' }))).toBe(true);
  });

  it('stays quiet when both halves are the empty string the API sends for absent', () => {
    expect(hasFault(run({ status: 'FAILED', errorCode: '', error: '' }))).toBe(false);
    expect(hasFault(run({ status: 'COMPLETED' }))).toBe(false);
  });

  it('stays quiet while the run can still move — a live run has not failed yet', () => {
    expect(hasFault(run({ status: 'IN_PROGRESS', errorCode: 'UPSTREAM_TOOL_FAILURE', error: 'transient' }))).toBe(
      false,
    );
  });
});

describe('retrySaid', () => {
  it('acknowledges a retry whose row leaves the filter it was listed under', () => {
    expect(retrySaid(4, 4)).toBe('Queued 4 of 4 runs.');
    expect(retrySaid(3, 4)).toBe('Queued 3 of 4 runs.');
  });

  it('says nothing when nothing was queued — the rejects and problem panels already have', () => {
    expect(retrySaid(0, 4)).toBe('');
  });
});

/**
 * The chip counts and the two live tables above them are the same numbers, so they come from the
 * same responses. `GET /pipelines` has no group-by and a `PageResponse.total` counts the *query*,
 * so `running` and `pending` already carry theirs — asking a second time was two requests per 2s
 * tick and one number with two sources, which can disagree on screen.
 */
describe('Runs chip counts', () => {
  beforeEach(() => TestBed.resetTestingModule());

  /** Every `listRuns` call this screen makes, as (status, size). */
  function screen() {
    const calls: { status: string; size: number }[] = [];
    const totals: Record<string, number> = {
      PENDING: 2,
      IN_PROGRESS: 3,
      COMPLETED: 20,
      FAILED: 5,
      CANCELLED: 1,
      ALL: 31,
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: PipelinesService,
          useValue: {
            listRuns: (status = 'ALL', page = 0, size = 25) => {
              calls.push({ status, size });
              return of({ items: [], page, size, total: totals[status] ?? 0 });
            },
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(Runs);
    TestBed.tick();
    return { calls, el: fixture.nativeElement as HTMLElement };
  }

  function chip(el: HTMLElement, label: string): string {
    const found = [...el.querySelectorAll<HTMLElement>('.chips .chip')].find((b) =>
      b.textContent!.trim().startsWith(label),
    )!;
    return found.querySelector('.count')?.textContent?.trim() ?? '';
  }

  it('does not ask again for a total the live tables already carry', () => {
    const { calls } = screen();

    // A one-row query is a count; the two live statuses must not have one.
    const counted = calls.filter((c) => c.size === 1).map((c) => c.status);
    expect(counted).toEqual(['COMPLETED', 'FAILED', 'CANCELLED']);
  });

  it('takes the live counts off the live queries, and ALL from all five', () => {
    const { el } = screen();

    expect(chip(el, 'PENDING')).toBe('2');
    expect(chip(el, 'IN_PROGRESS')).toBe('3');
    expect(chip(el, 'FAILED')).toBe('5');
    // Summed here rather than asked for: the sixth query would be a number the other five hold.
    expect(chip(el, 'ALL')).toBe('31');
  });
});

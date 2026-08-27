import { describe, expect, it } from 'vitest';

import { hasFault, marker, retrySaid, shortUrl } from './runs';
import { RunSummary } from '../../api/generated';

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

  it('does not render CREATED as a step after a retry either — prepareRetry writes it back', () => {
    expect(marker(run({ status: 'IN_PROGRESS', phase: 'CREATED' }))).toBe('queued');
  });

  it('never repeats the DONE a FAILED run reports as its phase', () => {
    expect(marker(run({ status: 'FAILED', phase: 'DONE' }))).toBe('—');
  });

  it('says nothing for a run that has stopped, whatever phase it claims', () => {
    expect(marker(run({ status: 'COMPLETED', phase: 'DONE' }))).toBe('—');
    expect(marker(run({ status: 'CANCELLED', phase: 'OCR' }))).toBe('—');
  });

  it('falls through to queued rather than -1 on a phase this build does not know', () => {
    expect(marker(run({ status: 'IN_PROGRESS', phase: 'EMBED' }))).toBe('queued');
  });
});

describe('hasFault', () => {
  it('shows the reason a run failed', () => {
    expect(hasFault(run({ status: 'FAILED', errorCode: 'UPSTREAM_TOOL_FAILURE', error: 'yt-dlp exit 1' }))).toBe(true);
  });

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

describe('shortUrl', () => {
  it('drops the 32 characters of boilerplate that made every row look alike', () => {
    expect(shortUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('youtube.com/watch?v=dQw4w9WgXcQ');
  });

  it('leaves what is left inside the 34ch the row label truncates at', () => {
    expect(shortUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ').length).toBeLessThanOrEqual(34);
    expect(shortUrl('https://www.youtube.com/shorts/Hpvj7yaRlF4').length).toBeLessThanOrEqual(34);
  });

  it('keeps the video id in front of the query params that push a URL over that', () => {
    // The ellipsis lands after the id rather than on it, which is the whole point.
    expect(shortUrl('https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PLrAXtmErZ&index=7').slice(0, 34)).toBe(
      'youtube.com/watch?v=jNQXAC9IVRw&li',
    );
  });

  it('strips www. only as a prefix, never mid-host', () => {
    expect(shortUrl('https://wwwtube.example/watch?v=x')).toBe('wwwtube.example/watch?v=x');
  });

  it('hands back anything URL cannot parse — a half-typed paste is the row you need to read', () => {
    expect(shortUrl('not a url at all')).toBe('not a url at all');
    expect(shortUrl('')).toBe('');
  });
});

describe('retrySaid', () => {
  it('acknowledges a retry whose row leaves the filter it was listed under', () => {
    expect(retrySaid(1, 1)).toBe('Queued 1 run.');
    expect(retrySaid(4, 4)).toBe('Queued 4 runs.');
  });

  it('names both numbers when the server took only some of them', () => {
    expect(retrySaid(3, 4)).toBe('Queued 3 of 4 runs.');
  });

  it('says nothing when nothing was queued — the rejects and problem panels already have', () => {
    expect(retrySaid(0, 4)).toBe('');
    expect(retrySaid(0, 1)).toBe('');
  });
});

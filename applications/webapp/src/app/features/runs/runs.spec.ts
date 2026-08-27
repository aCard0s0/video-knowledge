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

describe('shortUrl', () => {
  it('drops the 32 characters of boilerplate that made every row look alike', () => {
    expect(shortUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('youtube.com/watch?v=dQw4w9WgXcQ');
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
    expect(retrySaid(4, 4)).toBe('Queued 4 of 4 runs.');
    expect(retrySaid(3, 4)).toBe('Queued 3 of 4 runs.');
  });

  it('says nothing when nothing was queued — the rejects and problem panels already have', () => {
    expect(retrySaid(0, 4)).toBe('');
  });
});

import { describe, expect, it } from 'vitest';

import { hasFault, marker } from './runs';
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

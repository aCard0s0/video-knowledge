import { describe, expect, it } from 'vitest';

import { isDefaultValue } from './url-state';

describe('isDefaultValue', () => {
  it('treats the empty-ish values as unset when a key declares no default', () => {
    expect(isDefaultValue('channel', '', {})).toBe(true);
    expect(isDefaultValue('page', 0, {})).toBe(true);
    expect(isDefaultValue('live', false, {})).toBe(true);
  });

  it('keeps anything else a key declares no default for', () => {
    expect(isDefaultValue('channel', 'LuxAlgo', {})).toBe(false);
    expect(isDefaultValue('page', 2, {})).toBe(false);
  });

  it('keeps a screen default that is a real value out of the URL', () => {
    // Three screens shipped writing one of these on load, with nothing chosen.
    expect(isDefaultValue('sortBy', 'createdAt', { sortBy: 'createdAt' })).toBe(true);
    expect(isDefaultValue('pane', 'transcript', { pane: 'transcript' })).toBe(true);
    expect(isDefaultValue('status', 'ALL', { status: 'ALL' })).toBe(true);
  });

  it('still writes the value the operator actually chose', () => {
    expect(isDefaultValue('sortBy', 'updatedAt', { sortBy: 'createdAt' })).toBe(false);
    expect(isDefaultValue('pane', 'frames', { pane: 'transcript' })).toBe(false);
    expect(isDefaultValue('status', 'FAILED', { status: 'ALL' })).toBe(false);
  });

  it('does not let the empty-ish rule swallow a false that is the choice', () => {
    // `onlyNew` starts true, so false is what the operator picked — and it is the one value a
    // shared link has to carry. A declared default is exhaustive for its key.
    expect(isDefaultValue('onlyNew', true, { onlyNew: true })).toBe(true);
    expect(isDefaultValue('onlyNew', false, { onlyNew: true })).toBe(false);
  });

  it('no longer treats ALL as universally empty — it is a status chip, not a concept', () => {
    expect(isDefaultValue('eventType', 'ALL', {})).toBe(false);
  });
});

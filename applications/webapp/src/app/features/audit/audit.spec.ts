import { describe, expect, it } from 'vitest';

import { isoOrEmpty } from './audit';
import { dayLabel } from '../../core/time';

/**
 * What this screen computes rather than renders: the instant a picked date sends, and the two
 * properties of `dayLabel` the template's day heading rests on. Both have been wrong in a way the
 * markup could not show.
 */
describe('isoOrEmpty', () => {
  it('names the same instant the operator picked, offset and all', () => {
    // `.slice(0, 19)` dropped the `Z` and the server answered 400 on every date filter, replacing
    // the table with the panel reporting it.
    const picked = '2026-08-26T10:00';
    expect(isoOrEmpty(picked)).toBe(new Date(picked).toISOString());
  });

  it('is empty for an absent or unparseable value, so the param is dropped rather than sent', () => {
    expect(isoOrEmpty('')).toBe('');
    expect(isoOrEmpty('whenever')).toBe('');
    // Not a partial-date guard: `new Date('2026-08-')` is a valid Aug 1, and only a hand-edited
    // URL can put one here — the date input itself emits '' or a complete value.
  });
});

describe('dayLabel', () => {
  it('is one string per day, which is what makes the heading appear exactly where it changes', () => {
    const day = dayLabel('2026-08-27T21:22:07.918Z');
    expect(dayLabel('2026-08-27T19:41:41.693Z')).toBe(day);
    expect(dayLabel('2026-08-26T00:34:50.247Z')).not.toBe(day);
  });

  it('is empty for a timestamp that will not parse, so no heading is drawn', () => {
    expect(dayLabel('')).toBe('');
  });

  it('groups by local day, agreeing with the clock beside it', () => {
    // An hour apart across a UTC midnight: whether they share a *local* day depends on the runner's
    // zone, and the heading has to say the same thing `clockTime` does either way.
    const late = '2026-08-26T23:30:00.000Z';
    const early = '2026-08-27T00:30:00.000Z';
    const sameLocalDay = new Date(late).toDateString() === new Date(early).toDateString();

    expect(dayLabel(late) === dayLabel(early)).toBe(sameLocalDay);
  });
});

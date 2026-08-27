import { describe, expect, it } from 'vitest';

import { dayMarks, isoOrEmpty } from './audit';
import { RunItemAuditEvent } from '../../api/generated';

/**
 * The two answers this screen computes rather than renders: what a picked date sends, and which
 * rows carry a day heading. Both have been wrong in a way the markup could not show.
 */
describe('isoOrEmpty', () => {
  it('sends the offset, because fromDate/toDate are OffsetDateTime', () => {
    // The `Z` is the fix: `.slice(0, 19)` dropped it and the server answered 400 on every date
    // filter, replacing the table with the panel reporting it.
    expect(isoOrEmpty('2026-08-26T10:00')).toMatch(/(Z|[+-]\d{2}:\d{2})$/);
  });

  it('names the same instant the operator picked', () => {
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

function event(id: string, occurredAt: string): RunItemAuditEvent {
  return { id, occurredAt } as RunItemAuditEvent;
}

describe('dayMarks', () => {
  it('marks the first row of each day and no other', () => {
    // Descending, as the feed is. Two days, so two headings over four rows.
    const marks = dayMarks([
      event('a', '2026-08-27T21:22:07.918Z'),
      event('b', '2026-08-27T19:41:41.693Z'),
      event('c', '2026-08-26T00:34:50.247Z'),
      event('d', '2026-08-26T00:34:12.100Z'),
    ]);

    expect([...marks.keys()]).toEqual(['a', 'c']);
    expect(marks.get('a')).not.toBe(marks.get('c'));
  });

  it('marks nothing when every row shares a day', () => {
    const marks = dayMarks([
      event('a', '2026-08-27T21:22:07.918Z'),
      event('b', '2026-08-27T19:41:41.693Z'),
    ]);

    expect([...marks.keys()]).toEqual(['a']);
  });

  it('skips a row whose timestamp will not parse rather than heading it blank', () => {
    const marks = dayMarks([event('a', ''), event('b', '2026-08-27T19:41:41.693Z')]);

    expect(marks.has('a')).toBe(false);
    expect(marks.has('b')).toBe(true);
  });

  it('groups by local day, which is what the clock beside it shows', () => {
    // 23:30Z and 00:30Z are one hour apart and land on two UTC days; whether they share a *local*
    // day depends on the runner's zone, and the heading has to agree with `clockTime` either way.
    const marks = dayMarks([
      event('a', '2026-08-27T00:30:00.000Z'),
      event('b', '2026-08-26T23:30:00.000Z'),
    ]);
    const sameLocalDay =
      new Date('2026-08-27T00:30:00.000Z').toDateString() ===
      new Date('2026-08-26T23:30:00.000Z').toDateString();

    expect(marks.has('b')).toBe(!sameLocalDay);
  });
});

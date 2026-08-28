import { describe, expect, it } from 'vitest';

import { rowDisclosure } from './disclosure';

describe('rowDisclosure', () => {
  it('opens one row at a time, and closes the one already open', () => {
    const d = rowDisclosure();
    expect(d.isOpen('a')).toBe(false);

    d.toggle('a');
    expect(d.isOpen('a')).toBe(true);

    // Opening another closes the first: two open messages on one list is two things to scroll past.
    d.toggle('b');
    expect(d.isOpen('a')).toBe(false);
    expect(d.isOpen('b')).toBe(true);

    d.toggle('b');
    expect(d.isOpen('b')).toBe(false);
  });

  it('ignores a row with no id rather than opening every one of them', () => {
    const d = rowDisclosure();
    d.toggle(undefined);
    expect(d.isOpen(undefined)).toBe(false);
    // The guard is on both sides: `open === null` must not read as "the row with no id is open".
    d.toggle('a');
    expect(d.isOpen(undefined)).toBe(false);
  });
});

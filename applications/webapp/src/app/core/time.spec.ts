import { describe, expect, it } from 'vitest';

import { humanAge, humanAgeCoarse } from './time';

const NOW = Date.parse('2026-08-28T12:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();

describe('humanAgeCoarse', () => {
  it('never reports seconds', () => {
    // The reading humanAge gives the same instant, and the reason this function exists.
    expect(humanAge(ago(46_200), NOW)).toBe('46.2s ago');
    expect(humanAgeCoarse(ago(46_200), NOW)).toBe('just now');
    expect(humanAgeCoarse(ago(2 * 60_000 + 5_000), NOW)).toBe('2m ago');
  });

  it('keeps minutes inside the hour and days past the day', () => {
    expect(humanAgeCoarse(ago(59 * 60_000), NOW)).toBe('59m ago');
    expect(humanAgeCoarse(ago(72 * 60_000), NOW)).toBe('1h 12m ago');
    expect(humanAgeCoarse(ago(50 * 3_600_000), NOW)).toBe('2d ago');
  });

  it('answers a clock running ahead of the server, and a missing value', () => {
    expect(humanAgeCoarse(ago(-5_000), NOW)).toBe('just now');
    expect(humanAgeCoarse(undefined, NOW)).toBe('—');
  });
});

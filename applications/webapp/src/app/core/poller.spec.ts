import { Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Poller } from './poller';

describe('Poller.every', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.resetTestingModule();
  });
  afterEach(() => vi.useRealTimers());

  function polling(intervalMs: number) {
    const fn = vi.fn();
    const poller = TestBed.inject(Poller);
    runInInjectionContext(TestBed.inject(Injector), () => poller.every(() => intervalMs, fn));
    return { poller, fn };
  }

  it('fires on its cadence, not on the first tick', () => {
    const { fn } = polling(2000);

    vi.advanceTimersByTime(1000);
    expect(fn).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1000);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  /**
   * The regression this pins. `fire()` — the unpause and the return-to-tab path — used to invoke
   * the raw callback without resetting its interval deadline, so the very next 1s tick saw a
   * deadline that had elapsed while the poller was stopped and fired the same callback again: two
   * full reload bursts within a second of every tab return, on every registered screen.
   */
  it('does not double-fire a poller whose interval elapsed while paused', () => {
    const { poller, fn } = polling(2000);

    vi.advanceTimersByTime(2000);
    expect(fn).toHaveBeenCalledTimes(1);

    poller.toggle(); // pause
    vi.advanceTimersByTime(5000); // well past the interval, nothing may fire
    expect(fn).toHaveBeenCalledTimes(1);

    poller.toggle(); // unpause fires immediately — that half is right
    expect(fn).toHaveBeenCalledTimes(2);

    // The next 1s tick must not fire again off the stale deadline.
    vi.advanceTimersByTime(1000);
    expect(fn).toHaveBeenCalledTimes(2);

    // And the cadence restarts from the unpause, not from before the pause.
    vi.advanceTimersByTime(1000);
    expect(fn).toHaveBeenCalledTimes(3);
  });
});

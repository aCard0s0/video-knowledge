import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';

import { actionState } from './action';

/**
 * Six screens share this state now, and none of them had a test on the arm-then-confirm path
 * before — the two rules asserted here are the ones that were open-coded eighteen times and got
 * missed at least once each.
 */
describe('actionState', () => {
  it('takes two presses, and the second is the one that goes', () => {
    const a = actionState();

    expect(a.confirm('row-1', 'Press again to delete row-1.')).toBe(false);
    expect(a.armed()).toBe('row-1');
    expect(a.said()).toBe('Press again to delete row-1.');

    expect(a.confirm('row-1')).toBe(true);
    expect(a.armed()).toBeNull();
  });

  /** Arming a different row moves the arm, so there is always a way out besides Cancel. */
  it('re-arms rather than firing when a different row is pressed', () => {
    const a = actionState();

    a.confirm('row-1');
    expect(a.confirm('row-2')).toBe(false);
    expect(a.armed()).toBe('row-2');
  });

  /** A chip that relabels itself needs no line, and must not wipe the last result to say nothing. */
  it('leaves the last message alone when the arm carries no warning', () => {
    const a = actionState();
    a.ok('OCR: 3.2s, 40 row(s)');

    a.confirm('OCR');
    expect(a.said()).toBe('OCR: 3.2s, 40 row(s)');
  });

  it('clears the last failure and the last message when the next request goes out', () => {
    const a = actionState();
    a.fail(new HttpErrorResponse({ status: 500 }));
    a.confirm('row-1', 'Press again.');

    a.start('row-1');

    expect(a.busy()).toBe('row-1');
    expect(a.armed()).toBeNull();
    expect(a.failure()).toBeNull();
    expect(a.said()).toBe('');
  });

  /**
   * The regression this rule exists for: "Deleted X." rendered beside a panel explaining that it
   * could not be deleted.
   */
  it('drops the success line when the request fails', () => {
    const a = actionState();
    a.start('row-1');
    a.ok('Deleted row-1.');

    a.start('row-1');
    a.fail(new HttpErrorResponse({ status: 409, statusText: 'Conflict' }));

    expect(a.said()).toBe('');
    expect(a.busy()).toBeNull();
    expect(a.failure()?.status).toBe(409);
  });

  /**
   * The two ways out of an arm are not the same call. Where the arm wrote a warning, that line goes
   * with it; where the control named its own consequence, `said` holds the *previous* result and
   * Escape must not throw it away.
   */
  it('keeps a silent arm’s last result on disarm and drops a warning on cancel', () => {
    const a = actionState();
    a.ok('OCR: 3.2s, 40 row(s)');
    a.confirm('OCR');
    a.disarm();
    expect(a.armed()).toBeNull();
    expect(a.said()).toBe('OCR: 3.2s, 40 row(s)');

    a.confirm('row-1', 'Press Confirm delete to remove row-1.');
    a.cancel();
    expect(a.armed()).toBeNull();
    expect(a.said()).toBe('');
  });

  it('answers "is anything busy" and "is this row busy" from the same signal', () => {
    const a = actionState();
    expect(a.isBusy()).toBe(false);

    a.start('row-1');
    expect(a.isBusy()).toBe(true);
    expect(a.isBusy('row-1')).toBe(true);
    expect(a.isBusy('row-2')).toBe(false);
  });
});

import { DestroyRef, Injectable, inject, signal } from '@angular/core';

import { humanAge } from './time';

/**
 * The REST API has no SSE and no websocket, so every "live" number here is polled. One shared
 * clock and one shared pause switch keep that honest: the rail shows when the last tick landed
 * and lets the operator stop it, and nothing polls while the tab is hidden.
 */
@Injectable({ providedIn: 'root' })
export class Poller {
  /** Ticks once a second while visible and running — every relative age reads from this. */
  readonly now = signal(Date.now());
  readonly paused = signal(false);
  readonly hidden = signal(document.visibilityState === 'hidden');
  readonly lastTick = signal(Date.now());

  /** Registered pollers, so returning to the tab can refresh immediately. */
  private readonly callbacks = new Set<() => void>();

  constructor() {
    const clock = setInterval(() => {
      if (!this.stopped()) this.now.set(Date.now());
    }, 1000);
    const onVisibility = () => {
      const wasHidden = this.hidden();
      this.hidden.set(document.visibilityState === 'hidden');
      if (this.stopped()) return;
      this.now.set(Date.now());
      // Coming back to the tab should not show a stale board for up to another interval — the
      // whole "fire a batch and check back later" habit lands on this moment.
      if (wasHidden) this.fire();
    };
    document.addEventListener('visibilitychange', onVisibility);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(clock);
      document.removeEventListener('visibilitychange', onVisibility);
    });
  }

  stopped(): boolean {
    return this.paused() || this.hidden();
  }

  /**
   * How long ago, against the shared clock — so one tick refreshes every age on the screen at once.
   *
   * Here rather than on each screen because `humanAge` needs a `now` and the only honest `now` is
   * this one: eight components wrote the same three-line method over it, and the panel extracted
   * from two of them made that eight rather than seven. A screen that wants a different clock says
   * so — the video screen's rerun timer and the rail's wall clock both tick on their own, because
   * this one stops while the operator has polling paused.
   */
  age(value: string | null | undefined): string {
    return humanAge(value, this.now());
  }

  toggle(): void {
    this.paused.update((p) => !p);
    if (!this.stopped()) {
      this.now.set(Date.now());
      this.fire();
    }
  }

  private fire(): void {
    for (const fn of this.callbacks) fn();
    this.lastTick.set(Date.now());
  }

  /**
   * Runs `fn` every `intervalMs()`, skipping ticks while paused or hidden. Registered from an
   * injection context so the interval dies with the component.
   */
  every(intervalMs: () => number, fn: () => void): void {
    // One second tick with a deadline, so a cadence change (idle 15s ⇄ live 2s) is picked up on
    // the next tick without rebuilding timers. Nothing fires on the first tick either, which is
    // deliberate: `intervalMs` usually reads a resource whose params read a required route input,
    // and those are not bound yet while a constructor runs (NG0950). The first fetch is the
    // resource's own.
    let last = Date.now();
    // What `fire()` invokes — not the raw `fn`. A fire resets the deadline like a tick does:
    // without that, returning to the tab (or unpausing) ran the callback and then the next 1s tick
    // saw a deadline that had elapsed while stopped and ran it *again* — two full reload bursts
    // within a second, on every registered screen, on every tab return.
    const run = () => {
      last = Date.now();
      fn();
    };
    const tick = setInterval(() => {
      if (this.stopped() || Date.now() - last < Math.max(1000, intervalMs())) return;
      run();
      this.lastTick.set(last);
    }, 1000);

    this.callbacks.add(run);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(tick);
      this.callbacks.delete(run);
    });
  }
}

/** Cadence in ms: fast while something can still change, slow otherwise. */
export const POLL_LIVE = 2000;
export const POLL_IDLE = 15000;

import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/**
 * The REST API has no SSE and no websocket, so every "live" number here is polled. One shared
 * clock and one shared pause switch keep that honest: the gutter shows when the last tick landed
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
    let handle: ReturnType<typeof setInterval> | undefined;
    let current = -1;

    const arm = () => {
      const ms = Math.max(1000, intervalMs());
      if (ms === current) return;
      current = ms;
      if (handle) clearInterval(handle);
      handle = setInterval(() => {
        if (this.stopped()) return;
        fn();
        this.lastTick.set(Date.now());
      }, ms);
    };

    // Deliberately not armed synchronously: `intervalMs` usually reads a resource whose params
    // read a required route input, and those are not bound while the constructor runs (NG0950).
    // The re-arm tick does the first arm a second later; the initial fetch is the resource's own.
    const rearm = setInterval(arm, 1000);
    this.callbacks.add(fn);

    inject(DestroyRef).onDestroy(() => {
      if (handle) clearInterval(handle);
      clearInterval(rearm);
      this.callbacks.delete(fn);
    });
  }
}

/** Cadence in ms: fast while something can still change, slow otherwise. */
export const POLL_LIVE = 2000;
export const POLL_IDLE = 15000;

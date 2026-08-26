import { Component, computed, input, output } from '@angular/core';

import { LaneSegment } from '../core/lane';
import { humanDuration } from '../core/time';

/**
 * The phase lane — this console's one visualization.
 *
 * Each of the ten phases takes width in proportion to the time it actually took, measured from
 * the audit trail. That is deliberate: an equal-width stepper would say every phase is the same
 * size, when in practice DOWNLOAD and OCR eat the run and PERSIST takes 300ms. Reading one lane
 * answers where the item is, where it died, and whether a phase is hung or merely slow.
 *
 * Segments are buttons: clicking one filters the audit timeline to that phase, which is also why
 * a 26px floor matters — a 200ms phase still has to be clickable. Voids are not: a phase that
 * never ran has no events, so filtering to it can only ever empty the trail.
 */
@Component({
  selector: 'vk-lane',
  template: `
    <ul
      class="lane"
      [class.dead]="dead()"
      [class.stopped-cancelled]="status() === 'CANCELLED'"
      [attr.aria-label]="'Phase timeline, ' + total()"
    >
      @for (seg of display(); track seg.phase) {
        <li
          class="seg"
          [class.done]="seg.state === 'done'"
          [class.live]="seg.state === 'live'"
          [class.failed]="seg.state === 'failed'"
          [class.cancelled]="seg.state === 'cancelled'"
          [class.skipped]="seg.state === 'skipped'"
          [class.pending]="seg.state === 'pending'"
          [class.many]="seg.merged > 1"
          [style.flexGrow]="grow(seg)"
        >
          <button
            type="button"
            [disabled]="!clickable(seg)"
            (click)="pick.emit(seg.phase)"
            [attr.aria-label]="describe(seg)"
            [title]="describe(seg)"
          >
            @if (seg.merged > 1) {
              <span class="merged mono">{{ seg.merged }}</span>
            } @else {
              <span class="ph mono">{{ seg.phase }}</span>
              <span class="dur mono">{{ seg.ms === null ? label(seg) : humanDuration(seg.ms) }}</span>
            }
          </button>
        </li>
      }
    </ul>
  `,
  styles: `
    .lane {
      display: flex;
      align-items: stretch;
      gap: 2px;
      list-style: none;
      margin: 0;
      padding: 0;
      min-width: 0;
    }

    .seg {
      flex-shrink: 1;
      flex-basis: 0;
      min-width: 26px;
      overflow: hidden;
      /* A 300ms phase only has room for one line; clipping "52ms" to "52i" looks like a bug. */
      container-type: inline-size;
    }

    .seg.skipped,
    .seg.pending {
      flex: 0 0 26px;
    }

    .seg.pending.many {
      flex: 0 0 44px;
    }

    .merged {
      display: block;
      font-size: 10px;
      text-align: center;
      color: var(--fg-muted);
    }

    button {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 1px;
      width: 100%;
      height: 40px;
      padding: 0 5px;
      border: 1px solid var(--border);
      border-radius: 2px;
      background: var(--muted);
      text-align: left;
      overflow: hidden;
    }

    /* A void is not a broken control, so it does not get the "not-allowed" cursor, and the global
       disabled rule must not strip the hatching that says which phases were turned off. */
    button:disabled {
      cursor: default;
      background: inherit;
    }

    .ph,
    .dur {
      display: block;
      font-size: 10px;
      line-height: 1.2;
      letter-spacing: 0.04em;
      white-space: nowrap;
      overflow: hidden;
    }

    .ph {
      color: var(--fg);
    }

    .dur {
      color: var(--fg-muted);
    }

    /* After .ph/.dur, not before: at equal specificity the later rule wins, so declaring this
       first left the display:block above standing and every narrow segment showed a clipped
       duration — the exact "52ms rendered as 52i" this rule exists to prevent. */
    @container (max-width: 74px) {
      .dur {
        display: none;
      }
    }

    .done button {
      background: rgba(74, 222, 128, 0.12);
      border-color: rgba(74, 222, 128, 0.35);
    }

    .live button {
      background: rgba(56, 189, 248, 0.16);
      border-color: var(--st-running);
      animation: breathe 2s ease-in-out infinite alternate;
    }

    .failed button {
      background: rgba(220, 38, 38, 0.18);
      border-color: var(--st-failed-fill);
      border-right-width: 3px;
    }

    .failed .ph {
      color: var(--st-failed);
    }

    .cancelled button {
      background: rgba(100, 116, 139, 0.2);
      border-color: var(--st-cancelled);
      border-right-width: 3px;
    }

    .cancelled .ph {
      color: var(--fg-muted);
    }

    .skipped button {
      background: repeating-linear-gradient(
        135deg,
        var(--st-skipped),
        var(--st-skipped) 3px,
        transparent 3px,
        transparent 6px
      );
      border-style: dashed;
    }

    .pending button {
      background: transparent;
      border-color: var(--st-skipped);
    }

    .skipped .ph,
    .pending .ph,
    .skipped .dur,
    .pending .dur {
      color: transparent;
    }

    /* Stopped before the first drawn phase: no segment can carry the outcome, so the whole track
       does. The row's own "never started" text is what actually says it — this is the echo. */
    .lane.dead .pending button {
      border-color: var(--st-failed-fill);
      background: rgba(220, 38, 38, 0.1);
    }

    .lane.dead.stopped-cancelled .pending button {
      border-color: var(--st-cancelled);
      background: rgba(100, 116, 139, 0.14);
    }

    .lane.dead .merged {
      color: var(--fg);
    }

    @keyframes breathe {
      from {
        opacity: 0.72;
      }
      to {
        opacity: 1;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .live button {
        animation: none;
      }
    }
  `,
})
export class Lane {
  readonly segments = input.required<LaneSegment[]>();
  /** The run item's status, for the case no segment can express: it stopped before phase one. */
  readonly status = input<string | undefined>();
  readonly pick = output<string>();

  protected readonly humanDuration = humanDuration;

  /**
   * Consecutive never-reached phases collapse into one void carrying their count. An item that
   * dies in METADATA otherwise renders as nine identical empty boxes, which reads as a broken
   * component rather than as "nine phases never ran" — and in a narrow column it is most of the
   * lane. Phases that were *skipped* stay separate: which ones were turned off is information.
   */
  protected readonly display = computed<(LaneSegment & { merged: number })[]>(() => {
    const out: (LaneSegment & { merged: number })[] = [];
    for (const seg of this.segments()) {
      const previous = out[out.length - 1];
      if (seg.state === 'pending' && previous?.state === 'pending') {
        previous.merged += 1;
        continue;
      }
      out.push({ ...seg, merged: 1 });
    }
    return out;
  });

  /**
   * The item is over and nothing on the track ran — reaped while it was still queued, so
   * `failedPhase` is the CREATED marker and there is no segment to cap. Without this the lane
   * announced "complete" for an item that had failed.
   */
  protected readonly dead = computed(
    () =>
      (this.status() === 'FAILED' || this.status() === 'CANCELLED') &&
      this.segments().every((s) => s.state === 'pending'),
  );

  protected readonly total = computed(() => {
    const stopped = this.segments().find((s) => s.state === 'failed' || s.state === 'cancelled');
    if (stopped) return `${stopped.state === 'cancelled' ? 'cancelled in' : 'failed in'} ${stopped.phase}`;
    const live = this.segments().find((s) => s.state === 'live');
    if (live) return `running ${live.phase}`;
    if (this.dead()) {
      return this.status() === 'CANCELLED' ? 'cancelled before any phase ran' : 'failed before any phase ran';
    }
    return this.status() === 'PENDING' ? 'not started' : 'complete';
  });

  /** Width ∝ measured time. Unmeasured phases keep their fixed 26px void. */
  protected grow(seg: LaneSegment): number {
    return seg.ms && seg.ms > 0 ? seg.ms : 1;
  }

  /** A phase that never ran has no audit events, so filtering the trail to it always empties it. */
  protected clickable(seg: LaneSegment): boolean {
    return seg.state !== 'skipped' && seg.state !== 'pending';
  }

  protected label(seg: LaneSegment): string {
    return seg.state === 'skipped' ? 'skipped' : 'not reached';
  }

  protected describe(seg: LaneSegment & { merged?: number }): string {
    if ((seg.merged ?? 1) > 1) {
      const never = `${seg.merged} phases from ${seg.phase} onwards were never reached`;
      return this.dead() ? `${never} — the item stopped before it ran` : never;
    }
    return this.describeOne(seg);
  }

  private describeOne(seg: LaneSegment): string {
    switch (seg.state) {
      case 'done':
        return `${seg.phase} completed in ${humanDuration(seg.ms)}`;
      case 'live':
        return `${seg.phase} running for ${humanDuration(seg.ms)}`;
      case 'failed':
        return `${seg.phase} failed after ${humanDuration(seg.ms)}`;
      case 'cancelled':
        return `${seg.phase} cancelled after ${humanDuration(seg.ms)}`;
      case 'skipped':
        return `${seg.phase} skipped — turned off for this run, or disabled on the server`;
      default:
        return `${seg.phase} not reached`;
    }
  }
}

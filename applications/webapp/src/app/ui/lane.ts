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
 * Segments are buttons: clicking one opens that phase's row in the trail below, which is also why
 * a 22px floor matters — a 200ms phase still has to be clickable. Voids are not: a phase that
 * never ran has no events, so opening it can only ever show an empty trail.
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
    /* One continuous rail rather than ten loose chips. The gaps used to make the lane read as a
       row of unrelated buttons; a single track with hairline dividers reads as one run advancing
       left to right, which is the only thing this component is trying to say. */
    .lane {
      display: flex;
      align-items: stretch;
      list-style: none;
      margin: 0;
      padding: 0;
      min-width: 0;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      background: var(--muted);
      overflow: hidden;
    }

    .seg {
      position: relative;
      flex-shrink: 1;
      flex-basis: 0;
      min-width: 22px;
      /* A 300ms phase only has room for one line; clipping "52ms" to "52i" looks like a bug. */
      container-type: inline-size;
    }

    .seg + .seg {
      border-left: 1px solid var(--border);
    }

    /* Width is proportional to duration, which buries the one thing the lane exists to show: a
       75ms OCR failure beside a successful 18.5s DIARIZE gets 22px and 3% of the track. Every
       other segment stays proportional — the outcome ones just get a floor wide enough to read
       their own label, which is also the width the container query needs to keep the duration. */
    .seg.failed,
    .seg.cancelled,
    .seg.live {
      min-width: 78px;
    }

    .seg.skipped,
    .seg.pending {
      flex: 0 0 22px;
    }

    .seg.pending.many {
      flex: 0 0 40px;
    }

    .merged {
      display: block;
      font-size: 10px;
      text-align: center;
      color: var(--fg-muted);
    }

    button {
      position: relative;
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 1px;
      width: 100%;
      height: 42px;
      padding: 6px 6px 0;
      border: 0;
      border-radius: 0;
      background: transparent;
      text-align: left;
      overflow: hidden;
    }

    /* The state cap. Colour rides a 3px bar along the top of every segment instead of a border
       box around it, so a 22px phase still declares its outcome at full strength — the border it
       replaced was the first thing a narrow segment lost. */
    button::before {
      content: '';
      position: absolute;
      inset: 0 0 auto 0;
      height: 3px;
      background: var(--cap, transparent);
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
      /* The hide-below-48px rule further down covers the segments too narrow to say anything; this
         is the band above it. FRAME_SAMPLE wants ~90px and TRANSCRIBE ~76px, so a phase that took
         5% of a four-minute run lands between the two and hard-clipped to TRANSCRI — which reads
         as a rendering fault, the same thing the 48px rule exists to stop. Truncated with an
         ellipsis it reads as truncated. Backticks are deliberately absent: this comment lives
         inside the styles template literal, and one would end it.

         min-width is what makes the ellipsis possible at all. These are flex items of the column
         button above, so min-width:auto resolves to their min-content size — the whole word, for
         nowrap text. The box was never narrower than its own text, so nothing overflowed *it*: the
         button clipped instead, which is a cut, not an ellipsis. */
      min-width: 0;
      text-overflow: ellipsis;
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

    /* Below this the phase name cannot be read, only guessed at: PERSIST rendered as "PE" beside
       DOWNLOAD as "DO" is noise that looks like a rendering fault. The cap and the tooltip carry
       the segment instead — the accessible name on the button is unchanged either way. */
    @container (max-width: 48px) {
      .ph {
        display: none;
      }
    }

    /* Fills go left-to-right so the eye reads direction along the track, not ten flat blocks. */
    .done button {
      --cap: var(--st-done);
      background: linear-gradient(
        to right,
        color-mix(in srgb, var(--st-done) 16%, transparent),
        color-mix(in srgb, var(--st-done) 9%, transparent)
      );
    }

    .live button {
      --cap: var(--st-running);
      background: color-mix(in srgb, var(--st-running) 16%, transparent);
    }

    /* A sweep along the segment, not the whole box pulsing: opacity breathing dimmed the label
       it was drawing attention to, and on a lane of ten it read as a repaint glitch. */
    .live button::after {
      content: '';
      position: absolute;
      inset: 0;
      background: linear-gradient(
        90deg,
        transparent,
        color-mix(in srgb, var(--st-running) 22%, transparent),
        transparent
      );
      animation: sweep 1.8s ease-in-out infinite;
    }

    .failed button {
      --cap: var(--st-failed-fill);
      background: color-mix(in srgb, var(--st-failed-fill) 18%, transparent);
      box-shadow: inset -3px 0 0 var(--st-failed-fill);
    }

    .failed .ph {
      color: var(--st-failed);
    }

    .cancelled button {
      --cap: var(--st-cancelled);
      background: color-mix(in srgb, var(--st-cancelled) 20%, transparent);
      box-shadow: inset -3px 0 0 var(--st-cancelled);
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
    }

    .pending button {
      background: transparent;
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
      --cap: var(--st-failed-fill);
      background: color-mix(in srgb, var(--st-failed-fill) 10%, transparent);
    }

    .lane.dead.stopped-cancelled .pending button {
      --cap: var(--st-cancelled);
      background: color-mix(in srgb, var(--st-cancelled) 14%, transparent);
    }

    .lane.dead .merged {
      color: var(--fg);
    }

    @keyframes sweep {
      from {
        transform: translateX(-100%);
      }
      to {
        transform: translateX(100%);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .live button::after {
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

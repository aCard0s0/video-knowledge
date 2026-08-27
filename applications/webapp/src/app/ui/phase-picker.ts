import { Component, computed, input, model } from '@angular/core';

import { OPTIONAL_PHASES, OptionalPhase, PHASE_REQUIRES } from '../core/domain';

/**
 * Which phases a new run should execute. Only the seven optional phases can be toggled —
 * METADATA, DOWNLOAD and PERSIST consume the source URL, so a run cannot start without them and
 * the server rejects them with 400.
 *
 * The model holds the *skipped* set, because that is what the API takes (`skipPhases`), but the
 * operator picks what to run: a checked chip means "do this".
 *
 * **A ticked chip has to mean the phase will actually run.** Two things can make that false, and
 * neither used to be visible: the deployment can have the phase off
 * (`vidingest.<phase>.enabled` — the compose defaults turn DIARIZE, FRAME_SAMPLE, OCR and
 * KNOWLEDGE off), and the phase can depend on another optional phase the operator just unticked.
 * Both render as an unavailable chip that says which it is, because the alternative is a lane
 * afterwards drawing a phase as "turned off for this run" when the operator had asked for it.
 */
@Component({
  selector: 'vk-phase-picker',
  template: `
    <fieldset>
      <legend>Phases to run</legend>
      <div class="chips">
        @for (phase of phases; track phase) {
          <label
            class="chip"
            [class.off]="isSkipped(phase)"
            [class.unavailable]="reason(phase)"
            [title]="reason(phase)"
          >
            <input
              type="checkbox"
              [checked]="!isSkipped(phase) && !reason(phase)"
              [disabled]="!!reason(phase)"
              (change)="toggle(phase)"
            />
            <span class="mono">{{ phase }}</span>
          </label>
        }
      </div>
      <p class="note mono muted">
        {{ chosen() }} · metadata, download and persist always run
      </p>
      <!--
        Each reason spelled out rather than left in the chips' title attribute, which is
        unreachable by touch and by keyboard — the same trap the rail's health chips were
        pulled out of.
      -->
      @if (serverOff().length) {
        <p class="note mono warn">
          disabled on this server, cannot be enabled per run: {{ serverOff().join(', ') }}
        </p>
      }
      @for (item of blocked(); track item.phase) {
        <p class="note mono warn">{{ item.phase }} needs {{ item.requires }}, which is not running</p>
      }
    </fieldset>
  `,
  styles: `
    fieldset {
      border: 0;
      margin: 0;
      padding: 0;
    }

    legend {
      font: 500 var(--fs-xs) / 1 var(--font-mono);
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--fg-muted);
      padding: 0;
      margin-bottom: var(--space-sm);
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-sm);
    }

    .chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      padding: 5px 9px;
      border: 1px solid var(--accent);
      border-radius: var(--radius);
      background: color-mix(in srgb, var(--accent) 12%, transparent);
      color: var(--fg);
      font-size: var(--fs-sm);
      letter-spacing: 0.04em;
      text-transform: none;
      cursor: pointer;
      transition:
        background var(--t-fast) ease,
        border-color var(--t-fast) ease,
        color var(--t-fast) ease;
    }

    .chip.off {
      border-color: var(--border);
      border-style: dashed;
      background: transparent;
      color: var(--fg-muted);
    }

    /* Not a choice the operator can make, and not the same thing as one they made: the dashed
       "off" chip says "you turned this off", so an unavailable one has to look like neither that
       nor an enabled one. */
    .chip.unavailable {
      border-color: var(--st-cancelled);
      border-style: dotted;
      background: transparent;
      color: var(--fg-muted);
      cursor: not-allowed;
      text-decoration: line-through;
      text-decoration-color: var(--st-cancelled);
    }

    input {
      margin: 0;
      accent-color: var(--accent);
    }

    input:disabled {
      cursor: not-allowed;
    }

    .note {
      margin: var(--space-sm) 0 0;
      font-size: var(--fs-xs);
    }

    .note.warn {
      color: var(--st-warn);
    }
  `,
})
export class PhasePicker {
  /** Phase names to send as `skipPhases`. */
  readonly skipped = model<string[]>([]);

  /**
   * Phases this deployment has turned off, from `GET /health/phases`. Defaults to none, so the
   * screens that do not fetch it behave exactly as before.
   */
  readonly disabled = input<string[]>([]);

  protected readonly phases = OPTIONAL_PHASES;
  protected readonly skippedSet = computed(() => new Set(this.skipped()));
  private readonly disabledSet = computed(() => new Set(this.disabled()));

  protected isSkipped(phase: OptionalPhase): boolean {
    return this.skippedSet().has(phase);
  }

  /** Why this phase cannot run, or `''` when it can. Empty string so it is falsy in the template. */
  protected reason(phase: OptionalPhase): string {
    if (this.disabledSet().has(phase)) return `${phase} is disabled on this server and cannot be enabled per run`;
    const requires = PHASE_REQUIRES[phase];
    if (requires && (this.skippedSet().has(requires) || this.disabledSet().has(requires))) {
      return `${phase} needs ${requires}, which is not running`;
    }
    return '';
  }

  /** What the operator chose, which is a separate thing from what the deployment allows. */
  protected readonly chosen = computed(() => {
    if (this.skipped().length) return `${this.skipped().length} phase(s) skipped for this run`;
    // "all optional phases enabled" is false the moment the server has any of them off, and it
    // sat directly above the line saying so.
    return this.serverOff().length || this.blocked().length
      ? 'nothing skipped for this run'
      : 'all optional phases enabled';
  });

  protected readonly serverOff = computed(() => this.phases.filter((p) => this.disabledSet().has(p)));

  protected readonly blocked = computed(() =>
    this.phases
      .filter((p) => !this.disabledSet().has(p))
      .map((phase) => ({ phase, requires: PHASE_REQUIRES[phase] }))
      .filter((x) => !!x.requires && (this.skippedSet().has(x.requires) || this.disabledSet().has(x.requires))),
  );

  protected toggle(phase: OptionalPhase): void {
    if (this.reason(phase)) return;
    const next = new Set(this.skipped());
    if (next.has(phase)) next.delete(phase);
    else next.add(phase);
    this.skipped.set([...next]);
  }
}

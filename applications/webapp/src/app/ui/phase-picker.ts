import { Component, computed, model } from '@angular/core';

import { OPTIONAL_PHASES, OptionalPhase } from '../core/domain';

/**
 * Which phases a new run should execute. Only the seven optional phases can be toggled —
 * METADATA, DOWNLOAD and PERSIST consume the source URL, so a run cannot start without them and
 * the server rejects them with 400.
 *
 * The model holds the *skipped* set, because that is what the API takes (`skipPhases`), but the
 * operator picks what to run: a checked chip means "do this".
 */
@Component({
  selector: 'vk-phase-picker',
  template: `
    <fieldset>
      <legend>Phases to run</legend>
      <div class="chips">
        @for (phase of phases; track phase) {
          <label class="chip" [class.off]="isSkipped(phase)">
            <input type="checkbox" [checked]="!isSkipped(phase)" (change)="toggle(phase)" />
            <span class="mono">{{ phase }}</span>
          </label>
        }
      </div>
      <p class="note mono muted">
        {{ skipped().length ? skipped().length + ' phase(s) skipped for this run' : 'all optional phases enabled' }}
        · metadata, download and persist always run
      </p>
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
      background: rgba(22, 163, 74, 0.12);
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

    input {
      margin: 0;
      accent-color: var(--accent);
    }

    .note {
      margin: var(--space-sm) 0 0;
      font-size: var(--fs-xs);
    }
  `,
})
export class PhasePicker {
  /** Phase names to send as `skipPhases`. */
  readonly skipped = model<string[]>([]);

  protected readonly phases = OPTIONAL_PHASES;
  protected readonly skippedSet = computed(() => new Set(this.skipped()));

  protected isSkipped(phase: OptionalPhase): boolean {
    return this.skippedSet().has(phase);
  }

  protected toggle(phase: OptionalPhase): void {
    const next = new Set(this.skipped());
    if (next.has(phase)) next.delete(phase);
    else next.add(phase);
    this.skipped.set([...next]);
  }
}

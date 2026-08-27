import { Component, computed, inject, model } from '@angular/core';

import { OPTIONAL_PHASES, OptionalPhase } from '../core/domain';
import { Capabilities } from '../core/capabilities';

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
          <!--
            A phase the server has switched off is shown, not hidden: which enrichment this
            deployment does is exactly what the operator came here to learn. It just cannot be
            ticked, because ticking it would promise work that will not happen.
          -->
          <label class="chip" [class.off]="isSkipped(phase)" [class.unavailable]="unavailable(phase)">
            <input
              type="checkbox"
              [checked]="!isSkipped(phase) && !unavailable(phase)"
              [disabled]="unavailable(phase)"
              (change)="toggle(phase)"
            />
            <span class="mono">{{ phase }}</span>
          </label>
        }
      </div>
      <p class="note mono muted">
        {{ skipped().length ? skipped().length + ' phase(s) skipped for this run' : 'all available phases enabled' }}
        @if (unavailableCount()) {
          · {{ unavailableCount() }} turned off on this server
        }
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

    /* Off by configuration, not by choice: same hatched read as a skipped lane segment, and
       not clickable, because no click here can turn it on. */
    .chip.unavailable {
      border-color: var(--border);
      border-style: dashed;
      background: repeating-linear-gradient(
        135deg,
        transparent 0 4px,
        color-mix(in srgb, var(--fg-muted) 14%, transparent) 4px 8px
      );
      color: var(--fg-muted);
      cursor: not-allowed;
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

  private readonly capabilities = inject(Capabilities);

  protected readonly phases = OPTIONAL_PHASES;
  protected readonly skippedSet = computed(() => new Set(this.skipped()));

  protected readonly unavailableCount = computed(
    () => OPTIONAL_PHASES.filter((p) => this.capabilities.disabledOnServer(p)).length,
  );

  protected unavailable(phase: OptionalPhase): boolean {
    return this.capabilities.disabledOnServer(phase);
  }

  protected isSkipped(phase: OptionalPhase): boolean {
    return this.skippedSet().has(phase);
  }

  protected toggle(phase: OptionalPhase): void {
    if (this.unavailable(phase)) return;
    const next = new Set(this.skipped());
    if (next.has(phase)) next.delete(phase);
    else next.add(phase);
    this.skipped.set([...next]);
  }
}

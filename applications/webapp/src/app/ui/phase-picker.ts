import { Component, computed, inject, model } from '@angular/core';

import { Capabilities } from '../core/capabilities';
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
          <!--
            A phase the server has switched off is shown, not hidden: which enrichment this
            deployment does is exactly what the operator came here to learn. It just cannot be
            ticked, because ticking it would promise work that will not happen.
          -->
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
      <p class="note mono muted">{{ chosen() }} · metadata, download and persist always run</p>
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
        <p class="note mono warn">
          {{ item.phase }} needs {{ item.requires }}, which is not running
        </p>
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
   * Which phases the deployment has off comes from the shared `Capabilities` singleton, not from
   * an input: all three screens that draw this picker need the same answer, and threading it in
   * per screen is how run detail ended up never asking at all.
   */
  private readonly capabilities = inject(Capabilities);

  protected readonly phases = OPTIONAL_PHASES;
  protected readonly skippedSet = computed(() => new Set(this.skipped()));
  private readonly disabledSet = computed(
    () => new Set(OPTIONAL_PHASES.filter((p) => this.capabilities.disabledOnServer(p))),
  );

  protected isSkipped(phase: OptionalPhase): boolean {
    return this.skippedSet().has(phase);
  }

  /**
   * Why each phase cannot run, keyed by phase; absent means it can.
   *
   * One pass, one place the rule is written. It used to be spelled out twice — once in `reason()`
   * per chip and once in the two summary computeds — which is two things to keep in step the next
   * time a phase grows a gate.
   */
  private readonly reasons = computed(() => {
    const out = new Map<OptionalPhase, string>();
    for (const phase of this.phases) {
      const requires = PHASE_REQUIRES[phase];
      if (this.disabledSet().has(phase)) {
        out.set(phase, `${phase} is disabled on this server and cannot be enabled per run`);
      } else if (
        requires &&
        (this.skippedSet().has(requires) || this.disabledSet().has(requires))
      ) {
        out.set(phase, `${phase} needs ${requires}, which is not running`);
      }
    }
    return out;
  });

  /** Empty string rather than undefined, so it is falsy in the template. */
  protected reason(phase: OptionalPhase): string {
    return this.reasons().get(phase) ?? '';
  }

  /** What the operator chose, which is a separate thing from what the deployment allows. */
  protected readonly chosen = computed(() => {
    if (this.skipped().length) return `${this.skipped().length} phase(s) skipped for this run`;
    // "all optional phases enabled" is false the moment anything is unavailable, and it sat
    // directly above the line saying so.
    return this.reasons().size ? 'nothing skipped for this run' : 'all optional phases enabled';
  });

  protected readonly serverOff = computed(() =>
    this.phases.filter((p) => this.disabledSet().has(p)),
  );

  protected readonly blocked = computed(() =>
    [...this.reasons().keys()]
      .filter((phase) => !this.disabledSet().has(phase))
      .map((phase) => ({ phase, requires: PHASE_REQUIRES[phase] })),
  );

  protected toggle(phase: OptionalPhase): void {
    if (this.reason(phase)) return;
    const next = new Set(this.skipped());
    if (next.has(phase)) next.delete(phase);
    else next.add(phase);
    this.skipped.set([...next]);
  }
}

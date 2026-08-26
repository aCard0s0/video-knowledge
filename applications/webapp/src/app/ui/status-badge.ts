import { Component, computed, input } from '@angular/core';
import { statusVar } from '../core/domain';

/**
 * Colour never carries state on its own: the token itself is the label, so the row reads the
 * same to anyone who cannot separate the ramp's green from its red.
 */
@Component({
  selector: 'vk-status',
  template: `<span class="mono" [style.color]="colour()">{{ status() || '—' }}</span>`,
  styles: `
    :host {
      display: inline-block;
      font-size: var(--fs-sm);
      letter-spacing: 0.04em;
    }
  `,
})
export class StatusBadge {
  readonly status = input<string | undefined>();
  protected readonly colour = computed(() => statusVar(this.status()));
}

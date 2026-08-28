import { Component, computed, input } from '@angular/core';

/**
 * The console's icon set, one entry per glyph.
 *
 * A component rather than inline `<svg>` in five templates: the row actions on four different
 * tables draw the same trash and the same check, and two copies of shared row furniture is exactly
 * how the action column drifted apart the first time. Geometry — stroke, size, fill — comes from
 * the global `svg.icon` rule, so an entry here is only the path.
 */
const PATHS = {
  refresh: ['M21 12a9 9 0 1 1-2.64-6.36', 'M21 3v6h-6'],
  trash: ['M4 7h16M10 4h4M6 7l1 13h10l1-13M10 11v6M14 11v6'],
  check: ['m5 13 4 4L19 7'],
  close: ['M6 6l12 12M18 6 6 18'],
  chevron: ['m6 9 6 6 6-6'],
} as const;

export type IconName = keyof typeof PATHS;

@Component({
  selector: 'vk-icon',
  template: `
    <svg class="icon" [class.spin]="spin()" viewBox="0 0 24 24" aria-hidden="true">
      @for (d of paths(); track d) {
        <path [attr.d]="d" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
    }
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  /** For an action whose label used to say "Syncing…": with the words gone the icon has to. */
  readonly spin = input(false);
  protected readonly paths = computed(() => PATHS[this.name()]);
}

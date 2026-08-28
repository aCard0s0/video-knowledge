import { Component } from '@angular/core';

/**
 * Settings.
 *
 * Empty on purpose: the section exists so there is somewhere for the console's own preferences to
 * land — the theme, the poll interval, the page size — rather than each one growing its own corner
 * of the rail. Nothing is configurable yet, and a screen that says so is better than one that
 * pretends to offer choices.
 */
@Component({
  selector: 'vk-settings',
  template: `
    <section class="panel">
      <div class="panel-head">
        <span class="eyebrow">settings</span>
      </div>
      <p class="quiet muted">
        Nothing to configure yet. The theme switch is in the rail; everything else the console does
        is decided by the server.
      </p>
    </section>
  `,
})
export class Settings {}

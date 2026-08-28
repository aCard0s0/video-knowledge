import { Component, computed, input, output } from '@angular/core';
import { blank } from '../core/domain';

/**
 * The error code on a row, and the control that opens the message it names.
 *
 * The message is free text from the phase that died: it opens identically every time and differs at
 * its tail, so on a list it took a full-width row under every failed record and said nothing the
 * code beside the status had not. The code rides the row; the message is one press away.
 *
 * A `<button>`, not a clickable `<span>`: it is the only way into that message, so it has to be
 * reachable by keyboard and announce its state. `aria-controls` points at the row the caller draws.
 */
@Component({
  selector: 'vk-error-code',
  template: `
    @if (shown()) {
      <button
        class="code-chip"
        type="button"
        [class.calm]="cancelled()"
        [attr.aria-expanded]="open()"
        [attr.aria-controls]="controls() || null"
        [title]="open() ? 'Hide the message' : 'Show the message'"
        (click)="toggled.emit()"
      >
        {{ code() }}
        <span class="sr-only">— {{ open() ? 'hide' : 'show' }} the message</span>
      </button>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    button {
      cursor: pointer;
      min-height: 24px;
    }
  `,
})
export class ErrorCode {
  readonly code = input<string | undefined>();
  readonly cancelled = input(false);
  readonly open = input(false);
  readonly controls = input<string | undefined>();
  readonly toggled = output<void>();

  protected readonly shown = computed(() => !blank(this.code()));
}

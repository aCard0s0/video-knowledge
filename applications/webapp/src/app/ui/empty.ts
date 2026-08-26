import { Component, input } from '@angular/core';

/** An empty screen states the fact and offers the action that fixes it. No mood, no apology. */
@Component({
  selector: 'vk-empty',
  template: `
    <div class="wrap">
      <p class="msg">{{ message() }}</p>
      <ng-content />
    </div>
  `,
  styles: `
    .wrap {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--space-md);
      border: 1px dashed var(--border);
      border-radius: var(--radius-panel);
      padding: var(--space-lg);
      margin: var(--space-md) 0;
    }

    .msg {
      margin: 0;
      color: var(--fg-muted);
      font-size: var(--fs-md);
    }
  `,
})
export class Empty {
  readonly message = input.required<string>();
}

import { Component, input } from '@angular/core';
import { ApiFailure } from '../core/problem';

/**
 * An HTTP failure, in full: status and title stay separate from the detail, validation fields
 * are listed one per line, and the correlation id is always shown because it is the only thing
 * that ties this screen to a line in the server log. Nothing here collapses into "an error
 * occurred", and it does not disappear on a timer.
 */
@Component({
  selector: 'vk-problem',
  template: `
    @if (failure(); as f) {
      <div class="wrap" role="alert">
        <div class="head mono">
          <span class="code">{{ f.status || 'ERR' }}</span>
          <span class="title">{{ f.title }}</span>
        </div>
        <p class="detail prose">{{ f.detail }}</p>
        @if (f.fields.length) {
          <dl class="fields mono">
            @for (field of f.fields; track field.field) {
              <dt>{{ field.field }}</dt>
              <dd>{{ field.message }}</dd>
            }
          </dl>
        }
        @if (f.correlationId) {
          <p class="corr mono muted">correlation id {{ f.correlationId }}</p>
        }
      </div>
    }
  `,
  styles: `
    .wrap {
      border: 1px solid var(--st-failed-fill);
      border-left-width: 3px;
      border-radius: var(--radius-panel);
      background: color-mix(in srgb, var(--st-failed-fill) 8%, transparent);
      padding: var(--space-md);
      margin-bottom: var(--space-md);
    }

    .head {
      display: flex;
      align-items: baseline;
      gap: var(--space-sm);
      font-size: var(--fs-sm);
    }

    .code {
      color: var(--st-failed);
      font-weight: 500;
    }

    .title {
      color: var(--fg);
      letter-spacing: 0.04em;
    }

    .detail {
      margin: var(--space-sm) 0 0;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .fields {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: var(--space-xs) var(--space-md);
      margin: var(--space-sm) 0 0;
      font-size: var(--fs-sm);
    }

    dt {
      color: var(--st-failed);
    }

    dd {
      margin: 0;
    }

    .corr {
      margin: var(--space-sm) 0 0;
      font-size: var(--fs-xs);
      user-select: all;
    }
  `,
})
export class Problem {
  readonly failure = input<ApiFailure | null>(null);
}

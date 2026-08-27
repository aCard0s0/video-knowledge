import { Component, input } from '@angular/core';
import { ItemResult } from '../api/generated';

/**
 * The items a retry refused, with the server's reason for each.
 *
 * A 202 from either retry endpoint does not mean the work was queued: `enqueueRetryBatch` answers
 * with a per-item verdict, and every item can come back REJECTED — already running, already
 * cancelled, no URL left to fetch. Discarding that body makes a refusal look exactly like a
 * success, so both screens that can fire a retry render this.
 *
 * Warn, not failure: nothing broke, the server declined. The failure ramp is for something the
 * operator has to fix.
 */
@Component({
  selector: 'vk-rejects',
  template: `
    @if (items().length) {
      <div class="panel rejects" role="status">
        <p class="eyebrow">not retried</p>
        <dl class="mono sm">
          @for (reject of items(); track reject.itemId ?? reject.url) {
            <!-- Not truncated: every YouTube URL shares a prefix, so a clipped one names no item. -->
            <dt>{{ reject.url || 'this run' }}</dt>
            <dd>{{ reject.reason || 'no reason given' }}</dd>
          }
        </dl>
      </div>
    }
  `,
  styles: `
    .rejects {
      padding: var(--space-sm) var(--space-md) var(--space-md);
      border-left: 3px solid var(--st-warn);
      margin-bottom: var(--space-md);
    }

    p {
      margin: 0 0 var(--space-sm);
      color: var(--st-warn);
    }

    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: var(--space-xs) var(--space-md);
      margin: 0;
    }

    dt {
      overflow-wrap: anywhere;
    }

    dd {
      margin: 0;
      color: var(--st-warn);
    }

    /* Side by side, a full YouTube URL leaves the reason a handful of characters. */
    @media (max-width: 767px) {
      dl {
        grid-template-columns: 1fr;
        gap: 0 0;
      }

      dd {
        margin-bottom: var(--space-sm);
      }
    }
  `,
})
export class Rejects {
  readonly items = input<ItemResult[]>([]);
}

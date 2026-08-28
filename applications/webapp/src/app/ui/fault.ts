import { Component, computed, input } from '@angular/core';
import { blank } from '../core/domain';

/**
 * A pipeline failure — a different animal from an HTTP failure, and never merged with one.
 * `errorCode` is a PipelineErrorCode enum (DUPLICATE_VIDEO, UPSTREAM_TOOL_FAILURE,
 * TRANSCRIPTION_FAILURE, INVALID_METADATA, UNEXPECTED); the message beside it is free text from
 * the phase that died. It is shown in full: selectable, wrapping, never truncated, because the
 * useful part is usually the tail ("sidecar unreachable, or the frames were removed from disk") —
 * which is also why it carries no measure: a 92ch cap only pushed that tail further down the wrap.
 */
@Component({
  selector: 'vk-fault',
  template: `
    @if (shown()) {
      <div class="wrap">
        @if (!blankCode() && !hideCode()) {
          <span class="code-chip" [class.calm]="cancelled()">{{ errorCode() }}</span>
        }
        <span class="msg">{{ error() || 'No message recorded.' }}</span>
      </div>
    }
  `,
  styles: `
    .wrap {
      display: flex;
      align-items: flex-start;
      gap: var(--space-sm);
      padding: var(--space-sm) 0 0;
    }

    .msg {
      font-family: var(--font-mono);
      font-size: var(--fs-sm);
      line-height: 1.5;
      color: var(--fg);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      user-select: text;
    }

    /* Side by side the badge is a nowrap chip, so on a phone it takes half the row and squeezes
       the message that explains it into a ~14ch column. Stack instead. */
    @media (max-width: 640px) {
      .wrap {
        flex-direction: column;
        gap: var(--space-xs);
      }
    }
  `,
})
export class Fault {
  readonly errorCode = input<string | undefined>();
  readonly error = input<string | undefined>();
  /**
   * Drops the red. A CANCELLED item carries an errorCode like any other (`DUPLICATE_VIDEO`), but
   * it is a decision the pipeline made on purpose, and the failure ramp says an operator has
   * something to fix.
   */
  readonly cancelled = input(false);
  /**
   * Drops the chip, not the message. The runs board and the run trail put the code on the row
   * itself — it is what opens this — so drawing it again here says the same word twice. The code
   * is still passed in: it is what decides "No message recorded." is worth saying.
   */
  readonly hideCode = input(false);

  protected readonly blankCode = computed(() => blank(this.errorCode()));
  protected readonly shown = computed(() => !this.blankCode() || !blank(this.error()));
}

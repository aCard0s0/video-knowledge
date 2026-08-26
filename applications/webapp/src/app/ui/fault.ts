import { Component, computed, input } from '@angular/core';
import { blank } from '../core/domain';

/**
 * A pipeline failure — a different animal from an HTTP failure, and never merged with one.
 * `errorCode` is a PipelineErrorCode enum (DUPLICATE_VIDEO, UPSTREAM_TOOL_FAILURE,
 * TRANSCRIPTION_FAILURE, INVALID_METADATA, UNEXPECTED); the message beside it is free text from
 * the phase that died. It is shown in full: selectable, wrapping, never truncated, because the
 * useful part is usually the tail ("sidecar unreachable, or the frames were removed from disk").
 */
@Component({
  selector: 'vk-fault',
  template: `
    @if (shown()) {
      <div class="wrap">
        @if (!blankCode()) {
          <span class="code mono">{{ errorCode() }}</span>
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

    .code {
      flex: 0 0 auto;
      font-size: var(--fs-xs);
      letter-spacing: 0.06em;
      color: var(--st-failed);
      border: 1px solid var(--st-failed-fill);
      border-radius: var(--radius);
      padding: 2px 6px;
      white-space: nowrap;
    }

    .msg {
      font-family: var(--font-mono);
      font-size: var(--fs-sm);
      line-height: 1.5;
      color: var(--fg);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      user-select: text;
      max-width: 92ch;
    }
  `,
})
export class Fault {
  readonly errorCode = input<string | undefined>();
  readonly error = input<string | undefined>();

  protected readonly blankCode = computed(() => blank(this.errorCode()));
  protected readonly shown = computed(() => !this.blankCode() || !blank(this.error()));
}

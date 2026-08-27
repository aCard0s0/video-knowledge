import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { OptionalPhase } from './domain';
import { PipelinesService } from '../api/generated';

/**
 * What this server will actually do, fetched once.
 *
 * A run's `skipPhases` says what the *request* opts out of. It is only half the answer: every
 * optional phase also gates on a `vidingest.<phase>.enabled` property, and four of them default
 * to off. Nothing exposed that, so the phase picker showed all seven ticked over "all optional
 * phases enabled" while the server was configured to skip most of them — a batch submitted for
 * OCR and knowledge extraction came back with neither and the screen never said why.
 *
 * A root singleton, so the screens that need it share one request rather than one each: the
 * answer is deployment configuration and cannot change while the console is open.
 */
@Injectable({ providedIn: 'root' })
export class Capabilities {
  private readonly pipelines = inject(PipelinesService);

  private readonly resource = rxResource({
    stream: () => this.pipelines.getPipelineCapabilities(),
  });

  private readonly enabled = computed(
    () => new Set(this.resource.hasValue() ? (this.resource.value().enabledPhases ?? []) : []),
  );

  /**
   * True only when the server has positively said this phase is off — never while the answer is
   * in flight or the request failed. Marking a phase unavailable on a failed fetch would be a
   * worse lie than the one this fixes.
   */
  disabledOnServer(phase: OptionalPhase): boolean {
    return this.resource.hasValue() && !this.enabled().has(phase);
  }

  /** How many uploads a channel sync fetches — the catalog is a window, not the channel size. */
  readonly channelSyncLimit = computed(() =>
    this.resource.hasValue() ? this.resource.value().channelSyncLimit : undefined,
  );
}

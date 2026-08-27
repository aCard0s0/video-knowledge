import { Component, computed, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { HealthService, ReadinessResult } from './api/generated';
import { Poller } from './core/poller';
import { humanDuration } from './core/time';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly health = inject(HealthService);
  protected readonly poller = inject(Poller);

  protected readonly ready = rxResource({ stream: () => this.health.readiness() });
  protected readonly ollama = rxResource({ stream: () => this.health.ollama() });

  /** Dependency checks change on the scale of restarts, not seconds. */
  constructor() {
    this.poller.every(
      () => 30_000,
      () => {
        this.ready.reload();
        this.ollama.reload();
      },
    );
  }

  /**
   * Readiness answers **503 with the full ReadinessResult** when a check fails
   * (`HealthController.readiness`), so the error body *is* the report — not a reason to discard it.
   * "videoPath not-writable: /data/videos" is the whole diagnosis, and it only ever arrives on a
   * failing response. Treating any error as "server unreachable" sent the operator to check a
   * server that was up and had already named the thing that broke.
   *
   * Status 0 — nothing answered — is the only unreachable there is. Anything else that is not a
   * ReadinessResult (a proxy's HTML error page) has no checks to show, so it reads unreachable too.
   */
  private readonly readiness = computed<ReadinessResult | null>(() => {
    if (this.ready.hasValue()) return this.ready.value() ?? null;
    const err = this.ready.error();
    const body = err instanceof HttpErrorResponse && err.status !== 0 ? err.error : null;
    return typeof body === 'object' ? (body as ReadinessResult | null) : null;
  });

  /** Nothing to report and an error to explain: a 503 ProblemDetail or a proxy's HTML lands here. */
  protected readonly unreachable = computed(() => !!this.ready.error() && !this.checks().length);

  protected readonly checks = computed(() =>
    Object.entries(this.readiness()?.checks ?? {}).map(([name, value]) => ({
      name,
      value,
      ok: String(value).startsWith('ok'),
    })),
  );

  protected readonly ollamaLabel = computed(() => {
    const o = this.ollama.value();
    if (!o) return 'ollama …';
    if (!o.reachable) return 'ollama unreachable';
    const running = o.runningModels?.length ?? 0;
    return running > 0 ? `ollama ${running} loaded` : 'ollama idle, 0 loaded';
  });

  protected readonly tickLabel = computed(() => `${humanDuration(this.poller.now() - this.poller.lastTick())} ago`);
}

import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';

import { HealthService } from './api/generated';
import { Poller } from './core/poller';
import { humanAge } from './core/time';

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

  protected readonly checks = computed(() =>
    Object.entries(this.ready.value()?.checks ?? {}).map(([name, value]) => ({
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

  protected readonly tickLabel = computed(() => humanAge(new Date(this.poller.lastTick()).toISOString(), this.poller.now()));
}

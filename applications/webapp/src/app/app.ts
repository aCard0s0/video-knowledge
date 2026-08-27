import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';

import { HealthService, ReadinessResult } from './api/generated';
import { Poller } from './core/poller';
import { humanDuration } from './core/time';

/** One dependency the pipeline needs: the name rides the strip, `detail` is the whole report. */
export interface Dep {
  name: string;
  ok: boolean;
  detail: string;
}

/**
 * Where you are, from the URL alone — no screen has to publish its title to the shell, and the
 * ids that only ever appeared inside a page heading are on the strip too. Ids are cut to the
 * same eight characters the run and video screens print.
 */
export function crumb(url: string): { section: string; leaf: string } {
  const [, section = '', id = ''] = url.split(/[?#]/)[0].split('/');
  return { section, leaf: id.length > 12 ? id.slice(0, 8) : decodeURIComponent(id) };
}

const NAV_KEY = 'vk.nav-collapsed';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly health = inject(HealthService);
  private readonly router = inject(Router);
  protected readonly poller = inject(Poller);

  protected readonly ready = rxResource({ stream: () => this.health.readiness() });
  protected readonly ollama = rxResource({ stream: () => this.health.ollama() });

  protected readonly where = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => crumb(e.urlAfterRedirects)),
    ),
    { initialValue: crumb(this.router.url) },
  );

  /** Rail state survives a reload; below 900px the CSS collapses it regardless. */
  protected readonly collapsed = signal(localStorage.getItem(NAV_KEY) === '1');

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

  protected toggleNav(): void {
    this.collapsed.update((c) => !c);
    localStorage.setItem(NAV_KEY, this.collapsed() ? '1' : '0');
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
  private readonly unreachable = computed(() => !!this.ready.error() && !this.checks().length);

  private readonly checks = computed(() =>
    Object.entries(this.readiness()?.checks ?? {}).map(([name, value]) => ({
      name,
      value,
      ok: String(value).startsWith('ok'),
    })),
  );

  /**
   * `/health/ready` and `/health/ollama` as one list, because the strip shows one count. Nothing
   * is summarised away: every entry is listed with its server-given value in the popover, and a
   * failing one is named on the strip itself.
   */
  protected readonly deps = computed<Dep[]>(() => {
    const deps: Dep[] = this.unreachable()
      ? [{ name: 'server', ok: false, detail: 'no answer from /health/ready' }]
      : this.checks().map((c) => ({ name: c.name, ok: c.ok, detail: String(c.value) }));

    // `value()` throws once a resource is in its error state, so the error branch comes first.
    if (this.ollama.error()) {
      deps.push({ name: 'ollama', ok: false, detail: 'GET /health/ollama failed' });
    } else if (this.ollama.hasValue()) {
      const o = this.ollama.value();
      const running = o?.runningModels?.length ?? 0;
      const state = !o?.reachable
        ? `unreachable · ${o?.baseUrl ?? ''}`
        : running > 0
          ? `${running} loaded`
          : 'idle, 0 loaded';
      deps.push({
        name: 'ollama',
        ok: !!o?.reachable,
        detail: o?.embedModel ? `${state} · embed ${o.embedModel}` : state,
      });
    }
    return deps;
  });

  protected readonly okDeps = computed(() => this.deps().filter((d) => d.ok));
  protected readonly badDeps = computed(() => this.deps().filter((d) => !d.ok));

  protected readonly tickLabel = computed(
    () => `${humanDuration(this.poller.now() - this.poller.lastTick())} ago`,
  );
}

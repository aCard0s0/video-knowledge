import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
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

export type Theme = 'light' | 'dark';

/**
 * Two states, not three. "System" as a stored value would need a live `matchMedia` listener and a
 * tri-state control to earn its keep; following the OS *until the operator chooses* costs one
 * branch and is what the token file already does for the pre-boot paint.
 */
export function resolveTheme(stored: string | null, prefersDark: boolean): Theme {
  if (stored === 'light' || stored === 'dark') return stored;
  return prefersDark ? 'dark' : 'light';
}

/**
 * One entry per section, because the rail draws each of them the same way and the active one also
 * carries the current id. Five hand-written copies of that markup was five places to forget.
 * Icons are Lucide paths; `d` is a list so a two-part glyph needs no special case.
 */
export const SECTIONS = [
  {
    path: 'ingest',
    label: 'Ingest',
    d: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3'],
  },
  {
    path: 'channels',
    label: 'Channels',
    d: ['M4 11a9 9 0 0 1 9 9M4 4a16 16 0 0 1 16 16M5 19h.01'],
  },
  { path: 'runs', label: 'Runs', d: ['M22 12h-4l-3 9L9 3l-3 9H2'] },
  {
    path: 'videos',
    label: 'Videos',
    d: [
      'M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z',
      'm16 10.5 6-3.5v10l-6-3.5',
    ],
  },
  { path: 'audit', label: 'Audit', d: ['M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01'] },
] as const;

/**
 * Every server timestamp carries an explicit UTC offset — the entities are `OffsetDateTime` and
 * every `now()` is `OffsetDateTime.now(ZoneOffset.UTC)`, so the wire form is
 * "2026-08-26T15:49:24.522757Z" whatever zone the JVM sits in. The rail names that clock once,
 * ticking, instead of each screen implying which one it is showing.
 *
 * `timeZone: 'UTC'` here is deliberate and is *not* the same thing as `core/time.ts`: a screen
 * renders an instant in the operator's local zone, while this is a UTC wall clock on purpose — the
 * one place the console states the zone the server's numbers are in.
 */
const UTC_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  hour12: false,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});
const UTC_DATE = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  weekday: 'short',
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

const NAV_KEY = 'vk.nav-collapsed';
const THEME_KEY = 'vk.theme';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  host: { '(document:keydown)': 'onKey($event)' },
})
export class App {
  private readonly health = inject(HealthService);
  private readonly router = inject(Router);
  protected readonly poller = inject(Poller);

  protected readonly ready = rxResource({ stream: () => this.health.readiness() });
  protected readonly llm = rxResource({ stream: () => this.health.llmStatus() });

  protected readonly where = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => crumb(e.urlAfterRedirects)),
    ),
    { initialValue: crumb(this.router.url) },
  );

  /** Rail state survives a reload; below 900px the CSS collapses it regardless. */
  protected readonly collapsed = signal(localStorage.getItem(NAV_KEY) === '1');

  protected readonly theme = signal<Theme>(
    resolveTheme(
      localStorage.getItem(THEME_KEY),
      matchMedia('(prefers-color-scheme: dark)').matches,
    ),
  );

  /** Dependency checks change on the scale of restarts, not seconds. */
  constructor() {
    // The attribute is what the tokens key off; the meta keeps the browser's own chrome in step
    // with --bg. Both are set on the document, so this is the one place allowed to touch it.
    effect(() => {
      const theme = this.theme();
      document.documentElement.dataset['theme'] = theme;
      document
        .querySelector('meta[name="theme-color"]')
        ?.setAttribute('content', theme === 'dark' ? '#020617' : '#f8fafc');
    });

    const clock = setInterval(() => this.instant.set(new Date()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(clock));

    this.poller.every(
      () => 30_000,
      () => {
        this.ready.reload();
        this.llm.reload();
      },
    );
  }

  /**
   * `Alt+1`…`Alt+5`, which is what the ordinals in the rail are for. Keyed on `event.code`, not
   * `event.key`: macOS turns Alt+1 into `¡` and Alt+2 into `™`, so the character is not the digit
   * that was pressed. A focused text field keeps the combination — the ingest screen is a large
   * textarea, and stealing a keystroke someone is typing into it is worse than a missing shortcut.
   */
  protected onKey(event: KeyboardEvent): void {
    if (!event.altKey || event.ctrlKey || event.metaKey) return;
    const target = event.target as HTMLElement | null;
    if (target?.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target?.tagName ?? ''))
      return;

    const section = SECTIONS[Number(event.code.replace('Digit', '')) - 1];
    if (!section || !event.code.startsWith('Digit')) return;

    event.preventDefault();
    void this.router.navigate(['/', section.path]);
  }

  protected toggleTheme(): void {
    this.theme.update((t) => (t === 'dark' ? 'light' : 'dark'));
    localStorage.setItem(THEME_KEY, this.theme());
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
   * `/health/ready` and `/health/llm` as one list, because the strip shows one count. Nothing
   * is summarised away: every entry is listed with its server-given value in the popover, and a
   * failing one is named on the strip itself.
   */
  protected readonly deps = computed<Dep[]>(() => {
    const deps: Dep[] = this.unreachable()
      ? [{ name: 'server', ok: false, detail: 'no answer from /health/ready' }]
      : this.checks().map((c) => ({ name: c.name, ok: c.ok, detail: String(c.value) }));

    // `value()` throws once a resource is in its error state, so the error branch comes first.
    if (this.llm.error()) {
      deps.push({ name: 'llm', ok: false, detail: 'GET /health/llm failed' });
    } else if (this.llm.hasValue()) {
      const l = this.llm.value();
      // runningModels is Ollama-only — no other runtime exposes a /api/ps analogue, so a 0 from
      // one of those means "not reported", not "nothing loaded". Report what that runtime can
      // actually answer instead of printing an idle count it never populated.
      const state = !l?.reachable
        ? `unreachable · ${l?.baseUrl ?? ''}`
        : l?.provider === 'ollama'
          ? `${l.runningModels?.length ?? 0} loaded`
          : `${l?.installedModels?.length ?? 0} available`;
      const detail = [state, l?.provider, l?.embedModel && `embed ${l.embedModel}`]
        .filter(Boolean)
        .join(' · ');
      deps.push({ name: 'llm', ok: !!l?.reachable, detail });
    }
    return deps;
  });

  protected readonly okDeps = computed(() => this.deps().filter((d) => d.ok));
  protected readonly badDeps = computed(() => this.deps().filter((d) => !d.ok));

  protected readonly sections = SECTIONS;

  /**
   * Its own interval, not `poller.now()`: that clock stops while polling is paused, which is right
   * for a live lane segment and wrong for a wall clock. A stopped clock that still looks like a
   * clock is the one thing this must not be.
   */
  private readonly instant = signal(new Date());

  protected readonly utcTime = computed(() => UTC_TIME.format(this.instant()));
  protected readonly utcDate = computed(() => UTC_DATE.format(this.instant()));

  /** What `<time datetime>` wants: the same instant, machine-readable. */
  protected readonly utcIso = computed(() => this.instant().toISOString());

  /** `01`, `02`, … beside each section: the rail reads as the menu it is. */
  protected readonly ordinal = (i: number) => String(i + 1).padStart(2, '0');

  /** Just the duration: the rail spells out "updated … ago" only when it is wide enough to. */
  protected readonly tickAge = computed(() =>
    humanDuration(this.poller.now() - this.poller.lastTick()),
  );
}

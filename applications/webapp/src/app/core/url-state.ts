import { DestroyRef, WritableSignal, effect, inject, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

/**
 * Whether a value is the one its screen starts on, and therefore has no business in the URL.
 *
 * A screen whose default is a real value — `sortBy` at `createdAt`, `pane` at `transcript`,
 * `onlyNew` at `true` — declares it, and then only that value counts. Everything else falls back to
 * the empty-ish set, which means "unset" on every screen here.
 *
 * The two rules cannot be collapsed into one. `onlyNew` starts `true`, so `false` is the operator's
 * explicit choice — and the empty-ish rule would drop precisely that value from the URL, which is
 * the one a shared link exists to carry. A declared default is exhaustive for its key.
 *
 * `'ALL'` used to be hardcoded here beside the empty-ish values. It is a status chip on two screens,
 * not a general idea of emptiness, and while it sat here every screen with a real default — three of
 * six — wrote it into the URL on load: `?sortBy=createdAt`, `?pane=transcript`, `?onlyNew=true`.
 */
export function isDefaultValue(
  key: string,
  value: string | number | boolean,
  defaults: Record<string, string | number | boolean>,
): boolean {
  if (key in defaults) return value === defaults[key];
  return value === '' || value === 0 || value === false;
}

/**
 * Keeps a screen's filters, tab and page in the query string.
 *
 * Worth the wiring in an ops console: a link to "audit, ITEM_FAILED, last hour" is the thing you
 * paste to someone (or to yourself tomorrow), and browser Back stops being a trapdoor out of the
 * screen. Values are read once on entry and written with replaceUrl, so paging does not stack up
 * history entries.
 */
export function syncQueryParams(
  state: Record<string, WritableSignal<string | number | boolean>>,
  defaults: Record<string, string | number | boolean> = {},
): void {
  const router = inject(Router);
  const route = inject(ActivatedRoute);

  const initial = route.snapshot.queryParamMap;
  for (const [key, signal] of Object.entries(state)) {
    const raw = initial.get(key);
    if (raw === null) continue;
    const current = signal();
    if (typeof current === 'number') {
      const parsed = Number(raw);
      if (Number.isFinite(parsed)) signal.set(parsed);
    } else if (typeof current === 'boolean') {
      signal.set(raw === 'true');
    } else {
      signal.set(raw);
    }
  }

  const ref = effect(() => {
    const queryParams: Record<string, string | null> = {};
    for (const [key, signal] of Object.entries(state)) {
      const value = signal();
      // Defaults stay out of the URL so a shared link carries only what was actually chosen.
      queryParams[key] = isDefaultValue(key, value, defaults) ? null : String(value);
    }
    untracked(() =>
      router.navigate([], { relativeTo: route, queryParams, queryParamsHandling: 'merge', replaceUrl: true }),
    );
  });

  inject(DestroyRef).onDestroy(() => ref.destroy());
}

import { DestroyRef, WritableSignal, effect, inject, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

/**
 * Keeps a screen's filters, tab and page in the query string.
 *
 * Worth the wiring in an ops console: a link to "audit, ITEM_FAILED, last hour" is the thing you
 * paste to someone (or to yourself tomorrow), and browser Back stops being a trapdoor out of the
 * screen. Values are read once on entry and written with replaceUrl, so paging does not stack up
 * history entries.
 */
export function syncQueryParams(state: Record<string, WritableSignal<string | number | boolean>>): void {
  const router = inject(Router);
  const route = inject(ActivatedRoute);

  /**
   * Each signal's declared value *is* the default, captured before the URL is read.
   *
   * The rule used to be hardcoded — `'' | 'ALL' | 0 | false` — which is right only for a signal
   * that starts there. `onlyNew` starts `true`, so the one state worth putting in a link, the
   * filter turned *off*, was the one dropped: unticking wrote nothing, and a reload or a shared
   * URL silently restored the filter.
   */
  const defaults = Object.fromEntries(
    Object.entries(state).map(([key, signal]) => [key, signal()]),
  );

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
      queryParams[key] = value === defaults[key] ? null : String(value);
    }
    untracked(() =>
      router.navigate([], { relativeTo: route, queryParams, queryParamsHandling: 'merge', replaceUrl: true }),
    );
  });

  inject(DestroyRef).onDestroy(() => ref.destroy());
}

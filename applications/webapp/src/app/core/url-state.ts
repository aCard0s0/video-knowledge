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
      const isDefault = value === '' || value === 'ALL' || value === 0 || value === false;
      queryParams[key] = isDefault ? null : String(value);
    }
    untracked(() =>
      router.navigate([], { relativeTo: route, queryParams, queryParamsHandling: 'merge', replaceUrl: true }),
    );
  });

  inject(DestroyRef).onDestroy(() => ref.destroy());
}

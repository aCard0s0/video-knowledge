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
export function syncQueryParams(
  state: Record<string, WritableSignal<string | number | boolean>>,
  /**
   * Per-key guards for values that reach a query the server validates.
   *
   * A query param is whatever someone pasted. `?status=BOGUS` went straight into the signal, out to
   * `GET /videos`, and came back as `No enum constant …VideoStatus.BOGUS` in the failure panel —
   * while the `<select>` beside it, having no matching `<option>`, calmly displayed `ALL`. The
   * control contradicted the URL that produced the error, and the same shape sat on `/runs` and
   * `/audit` (`?status=` on both is a Java enum name one `valueOf` away from a 400).
   *
   * A rejected value is ignored, so the signal keeps its declared default and the write effect
   * below drops the key from the URL — the same self-healing `clampPage` gives a page past the end.
   *
   * A list for the enums, which is most of them; a predicate for the shapes a list cannot spell.
   * `?run=` on ingest and channel detail is a uuid — `GET /pipelines/{id}` answers a raw Java
   * conversion error for anything else, and the ids this console *shows* are `id.slice(0, 8)`,
   * which is exactly what gets half-copied into a URL. The guard belongs here rather than on the
   * signal: what the *server* hands those screens is always a whole id, and a screen that
   * second-guessed its own response would be guarding the wrong side.
   */
  allowed?: Record<string, readonly string[] | ((value: string) => boolean)>,
): void {
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
    const guard = allowed?.[key];
    if (guard && !(typeof guard === 'function' ? guard(raw) : guard.includes(raw))) continue;
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

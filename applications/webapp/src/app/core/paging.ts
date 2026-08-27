import { WritableSignal, effect } from '@angular/core';

/**
 * Pulls a page back to the first one once it sits past the end of the list.
 *
 * A page number outlives the list it came from. Delete the only row on page 2 and the response is
 * 0 rows with a total of 25: the screen renders its "nothing matches this filter" empty state over
 * 25 rows that do match, and the pager — which lives inside the non-empty branch — is gone with
 * them, so there is no way back except the nav bar. Same shape on a phase re-run that writes fewer
 * rows than the last one did: the pane claims the phase produced nothing while page 0 is full.
 *
 * Clamping is the whole fix. A page past the end is not a state worth preserving, and the
 * `syncQueryParams` effect drops it from the URL on the way out, so a shared link self-heals.
 *
 * Only a resource that actually holds a page is consulted: a loading or errored one says nothing
 * about where the end is (a server hiccup must not move the operator's page), and an idle one —
 * a pane that is not the visible one — has no page at all.
 */
export function clampPage(
  page: WritableSignal<number>,
  size: number,
  resource: { hasValue: () => boolean; value: () => { total?: number } | undefined },
): void {
  effect(() => {
    if (!resource.hasValue()) return;
    if (page() > 0 && page() * size >= (resource.value()?.total ?? 0)) page.set(0);
  });
}

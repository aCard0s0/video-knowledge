import { DestroyRef, inject } from '@angular/core';

/**
 * A filter write that waits for a pause in the typing.
 *
 * Every one of these writes is two things, not one: a request, and a `syncQueryParams` navigation
 * that the router cross-fades and scrolls to top. A box that filters as it is typed therefore costs
 * one of each per character — and on audit the first 35 of a hand-typed uuid all ask for the same
 * thing anyway, since `runIdInvalid` holds a partial id back from the query.
 *
 * Call it from an injection context: the pending timer is cleared when the screen is destroyed, so
 * a keystroke never lands a filter write on a component that has already gone.
 */
export function debouncedWrite<T>(apply: (value: T) => void, ms = 250): (value: T) => void {
  let pending: ReturnType<typeof setTimeout> | undefined;
  inject(DestroyRef).onDestroy(() => clearTimeout(pending));
  return (value: T) => {
    clearTimeout(pending);
    pending = setTimeout(() => apply(value), ms);
  };
}

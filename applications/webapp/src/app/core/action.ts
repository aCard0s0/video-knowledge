import { signal } from '@angular/core';

import { ApiFailure, toApiFailure } from './problem';

/**
 * What every screen that *does* something to the server has to track.
 *
 * Six screens carry an action — delete a video, sync or remove a channel, save or reset a
 * connection, re-run a phase, retry a run, rename a speaker — and each had written the same four
 * signals and the same three transitions by hand. `settings.ts` had already extracted the pair of
 * them (`start`/`fail`) locally, which is what says this belongs one level up: the shape was not
 * settings-specific, only its first extraction was.
 *
 * The four are one state, not four unrelated flags, and that is the reason to keep them together:
 * every transition writes several at once. Starting clears the last failure *and* the last
 * message, or a fresh press reads as having produced the previous answer. Failing clears the
 * message, or the screen says "Deleted X." beside a panel explaining that it could not be. Those
 * two rules were open-coded eighteen times, and `sync` on the channels list was the one that
 * missed the first of them.
 *
 * `K` keys the action, so one instance covers a list: which row is armed and which row is in
 * flight, rather than a boolean that disables every row's button. A screen with two independent
 * actions whose messages render in different places takes two instances — the video screen's
 * phase re-run and speaker rename are that, and sharing one `said` would print a rename into the
 * re-run note.
 *
 * Deliberately not here: the rejects panel (`vk-rejects` takes `ItemResult[]`, which is the server
 * declining rather than failing), and the per-screen wording. What was said is the screen's
 * business; that *something* has to be said is not.
 *
 * Two screens deliberately do **not** use this. The runs board fires a retry per FAILED run on the
 * page, so its in-flight state is a `Set` of ids rather than one key — a batch is a different
 * shape, not a variation on this one. And ingest has no armed state and no status line: submit and
 * retry are two booleans and a headline, so half of this would sit unused.
 */
export function actionState<K extends string = string>() {
  /** Which action is in flight, by key. Null is the resting state. */
  const busy = signal<K | null>(null);
  /** Which action is one press from happening. Null is the resting state. */
  const armed = signal<K | null>(null);
  /** What the last press did, for the `role="status"` line. Empty is the resting state. */
  const said = signal('');
  const failure = signal<ApiFailure | null>(null);

  return {
    busy,
    armed,
    said,
    failure,

    /** No key: is anything in flight. With one: is *this* row. */
    isBusy: (key?: K): boolean => (key === undefined ? busy() !== null : busy() === key),

    /**
     * Two-press confirm: the first press arms and returns false, the second returns true.
     *
     * A `warning` is written to the status line, so the consequence is announced rather than only
     * drawn — the native `confirm()` this pattern replaced took focus out of the document and
     * handed it back to `<body>`. Omit it where the control names its own consequence (a chip that
     * relabels itself "re-run OCR?"), and the last message stands rather than being cleared by an
     * arm that has not done anything yet.
     *
     * Pressing a different key re-arms that one, so there is always a way out besides Cancel.
     */
    confirm: (key: K, warning?: string): boolean => {
      if (armed() === key) {
        armed.set(null);
        return true;
      }
      armed.set(key);
      if (warning !== undefined) said.set(warning);
      return false;
    },

    /** Backed out of an arm that said nothing — the control named its own consequence. */
    disarm: (): void => {
      armed.set(null);
    },

    /**
     * Backed out of an arm that wrote a warning, which goes with it: leaving "Press Confirm delete
     * to remove X." standing over a row that is no longer armed is a stale instruction.
     *
     * Not the same call as {@link disarm}, and the difference is not cosmetic: where the arm was
     * silent, `said` holds the *previous* action's result — "OCR: 3.2s, 40 row(s)" — and wiping it
     * on Escape would throw away the only record of the re-run that just happened.
     */
    cancel: (): void => {
      armed.set(null);
      said.set('');
    },

    /** The request is going out: nothing from the last one is still true. */
    start: (key: K): void => {
      busy.set(key);
      armed.set(null);
      failure.set(null);
      said.set('');
    },

    /**
     * It worked. The message is not optional in practice: a row that leaves the table, a value
     * saved over itself, a retry that moves a run out of the filter it was listed under — each is
     * indistinguishable from a press that did nothing.
     */
    ok: (message = ''): void => {
      busy.set(null);
      said.set(message);
    },

    /** It did not. One translator for every failure in this app — see `toApiFailure`. */
    fail: (err: unknown): void => {
      busy.set(null);
      said.set('');
      failure.set(toApiFailure(err));
    },
  };
}

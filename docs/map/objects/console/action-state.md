---
type: object
cluster: console
universe: live
status: verified
verified: 2026-09-03
commit: 6f5dc86
entity: applications/webapp/src/app/core/action.ts
---

# actionState — what a screen tracks while it does something

The four signals every mutating screen carries: `busy`, `armed`, `said`, `failure`. One factory,
six callers, keyed so a single instance covers a list.

## Why this shape

- **They are one state, not four flags.** Every transition writes several at once: `start` clears
  the last failure *and* the last message, `fail` clears the message, `ok` sets it (`action.ts:94`,
  `:106`, `:112`). Open-coded on six screens, the first rule was missed by `sync` on the channels
  list — a failed sync could sit under the previous "Synced X." line.
- **`settings.ts` had already extracted the pair** (`start`/`fail`) *locally*. The shape was never
  settings-specific; only its first extraction was.
- **`busy` and `armed` are keyed, not booleans.** A list needs to know *which* row is armed and
  *which* is in flight; a boolean disables every row's button. `isBusy()` answers "anything",
  `isBusy(key)` answers "this one" (`action.ts:52`).
- **Two ways out of an arm, and the difference is behavioural.** `cancel()` drops the warning the
  arm wrote ("Press Confirm delete to remove X."), because a stale instruction over an unarmed row
  is worse than none. `disarm()` keeps `said`, because where the control names its own consequence
  (a chip that relabels itself "re-run OCR?") the line is holding the *previous* action's result and
  Escape must not throw it away (`action.ts:76-92`, asserted in `action.spec.ts`).
- **A screen with two independent actions takes two instances.** The video screen's phase re-run and
  speaker rename render their messages in different panes; one `said` would print a rename into the
  re-run note. They still share one failure panel — that is `firstFailure`'s job, not this state's.

## Shape

- `actionState<K extends string>()` — `action.ts:36`; a plain factory, no injection context needed
- `confirm(key, warning?)` → boolean: first press arms and returns false, second returns true
- `start(key)` / `ok(message)` / `fail(err)` / `disarm()` / `cancel()` / `isBusy(key?)`
- Depends on `toApiFailure` (`core/problem.ts`) — the one translator in this app

## Connected to

- **joins:** [ApiFailure](api-failure.md) — `failure` is an `ApiFailure`, rendered by `vk-problem`
- **looks-like-but-is-not:** the rejects panel. `vk-rejects` takes `ItemResult[]`, which is the
  server *declining* on purpose rather than failing, and never merges with `failure`

## If you change this

- **Hits:** `features/videos/videos`, `features/videos/video-detail` (two instances),
  `features/channels/channels`, `features/channels/channel-detail`, `features/settings/settings`,
  `features/runs/run-detail` — and their templates, which read the signals directly.
- **Does not hit:** the **runs board**, whose retry is a batch and whose in-flight state is a `Set`
  of ids, and **ingest**, which has no armed state and no status line. Both are deliberate.

## Surfaces

| Surface | Role |
|---|---|
| six feature components | read the signals in their templates, drive the transitions |
| `styles.scss` `.said` | the one class the `role="status"` line uses on every screen |

## See

- Source: `applications/webapp/src/app/core/action.ts`, `core/action.spec.ts`

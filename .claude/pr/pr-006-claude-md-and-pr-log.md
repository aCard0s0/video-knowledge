# PR #6 — CLAUDE.md landmines and the PR log

**Merged**: 2026-08-25 · **Branch**: `docs/claude-md-and-pr-log`

Documentation only — no production code touched.

## Problem

Two gaps, both surfaced by [#5](pr-005-vidingest-defect-review.md).

`CLAUDE.md` was accurate but silent on the traps that actually cost time in that PR. It said
"every phase service is idempotent (wipe-then-repopulate)" without saying that the wipe and the
repopulate were not in the same transaction, and nothing warned that `@Transactional` on a
`protected` or self-invoked method does nothing — a mistake already made in six services. It
also carried a `-Dtest=` example that fails the build on sibling modules.

Separately, the reasoning behind a merged PR lived only in the GitHub PR body. Decisions that
were settled deliberately — and the options rejected for concrete reasons — were invisible to a
session working from the checkout, which invites re-litigating them.

## Change

`CLAUDE.md`, 141 → 184 lines. Only additions that were wrong or that cost real time:

- The `-Dsurefire.failIfNoSpecifiedTests=false` pairing for `-Dtest=` with `-am`.
- The transaction rule, with the replacement pattern (`TransactionOperations` around the DB
  block) and the hard "never open a transaction around a sidecar / ollama / yt-dlp call".
- Which phases wipe-and-repopulate atomically and which two deliberately do not.
- The run-row lock in `refreshRunState`, including the explicit *do not extend it* to
  `ensureRunInProgress`/`updateRunPhase`.
- Test facts: no `src/test/resources`, `@MockitoBean` not `@MockBean`, `PipelinePhase` is
  sealed, `TransactionOperations.withoutTransaction()` in unit tests.
- Corrected the reactor module count.

`.claude/pr/` holds one decision record per merged PR, indexed in its README. #1, #3 and #4
were backfilled from their actual PR bodies.

## Decisions

- **Decision record, not changelog.** The diff is in `git log` and on GitHub; what is not
  recoverable is why a trade-off was settled and what was rejected. Hence **Decisions** and
  **Deliberately not done** as required sections, with an explicit test in the README: if a
  future session would otherwise re-propose something the PR already rejected, the rejection
  belongs in the file.
- **Backfilled #1/#3/#4 rather than starting at #6.** Their PR bodies carried real decision
  content, so the summaries are derived, not invented. #2 was auto-closed unmerged by GitHub
  and is recorded as such rather than silently skipped.
- **Merge commit is optional in the format.** The first draft required it, which the very PR
  introducing the convention could not satisfy — the file is authored before the merge exists.
- **`CLAUDE.md` grew ~20%.** Justified per addition: each is a landmine that caused a real bug
  or a wasted round-trip. The file is loaded every session, so anything that does not meet that
  bar was left out.

## Deliberately not done

- **No `.gitignore` entry for `.claude/`.** The PR log is meant to be committed. That does mean
  any local-only files placed there later will show up in `git status`; an ignore rule scoped to
  something like `.claude/settings.local.json` is the right fix if that becomes a problem, and
  is not this PR's business.
- **No rewrite of the docs under `docs/`.** Those are user-facing companions with their own
  "Last reviewed" convention; `CLAUDE.md` and the PR log are agent-facing. Different audiences,
  kept separate.

## Follow-ups

None.

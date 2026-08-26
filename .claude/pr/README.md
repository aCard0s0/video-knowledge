# PR log

One file per merged PR. This is a decision record, not a changelog — the diff is on GitHub
and in `git log`. What is *not* recoverable from those is why a trade-off was settled the way
it was, and what was deliberately left undone. That is what goes here.

The test: if a future session would otherwise re-propose something this PR already rejected,
the rejection belongs in the file.

## Format

Filename `pr-NNN-<slug>.md`, zero-padded to three digits, slug from the branch name.

```markdown
# PR #N — <title>

**Merged**: YYYY-MM-DD · **Branch**: `<branch>`

## Problem
What was actually wrong. Name the failure, not the code smell.

## Change
What was done, in a few lines. Link files as `path/to/File.java`.

## Decisions
The calls that could reasonably have gone the other way, each with its reason.

## Deliberately not done
Rejected options and why. The most valuable section — this is what stops the next
session re-litigating.

## Follow-ups
Open work this PR knowingly left behind, or "none".
```

Keep it under ~60 lines. Add the file and its row in the index below in the same PR.

The file is normally authored *in* the PR, before a merge commit exists, so the SHA is not a
required field — append `· **Merge commit**: <sha>` only when writing one up after the fact.
`gh pr view <N> --json mergeCommit` recovers it any time.

## Index

| PR | Date | Summary |
|----|------|---------|
| [#9](pr-009-process-liveness-and-atomicity.md) | 2026-08-26 | Every ffmpeg call bounded so a hung one stops wedging ingestion; run items orphaned as PENDING become recoverable; knowledge/OCR replaces commit atomically; yt-dlp out of every transaction; toolchain moved to Java 26 |
| [#8](pr-008-phase-toggle-and-read-model.md) | 2026-08-26 | Six positional skip booleans collapse into one `skipPhases` set; one writer for `PipelineRun` status; one copy of run-preview ranking; run items leased so instances stop reaping each other |
| [#7](pr-007-reconciler-liveness-and-fanout-bounds.md) | 2026-08-25 | Reconciler stops failing live items; ffmpeg staged so a failure keeps the previous frames; youtube sync fan-out bounded |
| [#6](pr-006-claude-md-and-pr-log.md) | 2026-08-25 | CLAUDE.md gains the transaction and run-status landmines; this PR log established |
| [#5](pr-005-vidingest-defect-review.md) | 2026-08-25 | Five defects: non-atomic wipe-then-repopulate, silent OCR loss, lost run-status updates, connection held across the embeddings call, diarize N+1 |
| [#4](pr-004-controllers-delegate-to-services.md) | 2026-08-25 | Three controllers reaching for repositories directly moved behind four new query services |
| [#3](pr-003-unify-failure-translation.md) | 2026-08-25 | One `PhaseFailureException` supertype; the rerun endpoint stops answering 200 on failure |
| [#1](pr-001-per-phase-rerun-registry.md) | 2026-08-25 | Per-phase rerun routes through `PipelinePhaseRegistry` instead of a hand-written switch |

PR #2 was auto-closed unmerged by GitHub when its stacked base branch was deleted; it was
recreated as #3.

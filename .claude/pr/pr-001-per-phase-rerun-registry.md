# PR #1 — Per-phase rerun routes through PipelinePhaseRegistry

**Merged**: 2026-08-25 · **Branch**: `fix/phase-rerun-uses-registry` · **Merge commit**: `55050b6`

## Problem

`VideoPhaseRunnerService` held a hand-written `switch` over seven phase names with eight
injected services, duplicating the mapping `PipelinePhaseRegistry` already owned. Adding a
phase meant touching five places, not the four `CLAUDE.md` documents — and the copy had
already drifted:

- `TranscribePhase` sets `TRANSCRIBING` and flips to `FAILED` on throw; `ContextPhase` does
  the same with `PROCESSING`. The runner called the services raw, so a *failed* rerun left the
  video sitting at `COMPLETED`.
- Row counts were re-derived a second time from whatever the service return values exposed.

## Change

The runner resolves the phase name to a `PipelineRunPhase`, looks the implementation up via
`PipelinePhaseRegistry.byPhase(...)`, and executes it with `PipelinePhaseContext.forRerun(video)`.
It is now timing, status handling and error normalisation — nothing else. 8 dependencies → 3.
A new phase becomes rerunnable by adding it to one `EnumSet`.

## Decisions

- **`applies(ctx)` stays bypassed.** It mixes per-run skip flags (meaningless for a rerun) with
  the `vidingest.<phase>.enabled` deployment toggles. This endpoint is the operator escape
  hatch — "re-OCR after a paddleocr-server upgrade" — so it forces the phase. Honouring the
  toggles instead is a one-line change if that is ever preferred.
- **Successful reruns restore the pre-call video status**; on failure the phase's own `FAILED`
  stands. Finalisation belongs to `PipelineService`, which a rerun does not go through.
- **`ResponseStatusException` → `IllegalArgumentException`** for phase validation, dropping
  `org.springframework.http` from the service. Same HTTP 400 via the handler.
- **Tests use the real sealed phase impls with mocked services** — `PipelinePhase` is sealed and
  cannot be stubbed, and this also pins the runner to what phases actually do to the video row.

## Deliberately not done

- No change to which phases are rerunnable. METADATA/DOWNLOAD/PERSIST consume a URL rather than
  a video row, so they still need a full pipeline run.

## Follow-ups

Superseded by [#3](pr-003-unify-failure-translation.md): this PR left failures surfacing as
HTTP 200 `{"status":"ERROR"}`, which #3 fixed.

# PR #3 — Unify failure translation across the API boundary

**Merged**: 2026-08-25 · **Branch**: `fix/unify-failure-translation` · **Merge commit**: `a9d8394`

Recreated from #2, which GitHub auto-closed when its stacked base branch was deleted on merge
of #1. Same branch, same commit, rebased on `main`.

## Problem

Three tables translated failures and none agreed. `PipelineErrorClassifier` knew 3 exception
types, so Diarization / FrameSampling / Fusion / KnowledgeExtraction / Ocr failures all landed
as `UNEXPECTED` — in a run item's `error_code`, a pyannote outage looked identical to a
null-pointer bug. `VidingestApiExceptionHandler` knew none of the six, so phase failures from
the regenerate endpoints became a generic 500. Worst, `VideoPhaseRunnerService` caught
everything and answered **HTTP 200** `{"status":"ERROR"}` — an error encoded in a body that
`VidingestClient`'s `catch (RestClientResponseException)` cannot see.

Separately, `IllegalStateException → 409` was mapped wholesale, so unexpected illegal state
from Spring or JDBC internals reached callers as a conflict worth retrying.

## Change

One supertype: the six `*FailureException` types extend `PhaseFailureException`. The classifier
gets one arm → `UPSTREAM_TOOL_FAILURE`, the handler one arm → 502. A phase added later is
covered by both without either table being edited. Subclasses stay concrete, so narrow catches
(e.g. `OcrService` swallowing one bad frame) still work. Explicit `ConflictException` at the
three real 409 sites; unexpected illegal states now correctly reach 500.

## Decisions

- **Correct status codes beat richer bodies.** Failures do not carry `phase`/`elapsedMs` as
  ProblemDetail properties: that needs a wrapper exception to hold them, which would flatten
  502/500/404 into one status since the handler could only see the wrapper. `elapsedMs` stays in
  the WARN log; `instance` already carries the phase.
- **`RunVideoPhaseResult` drops `status` and `message`** — with failures propagating they would
  be permanently `"OK"` and `null`.

## Deliberately not done

- No wrapper exception (see above).
- No compatibility shim for the 200-with-error-body shape.

## Breaking change

Any HTTP caller branching on `status == "ERROR"` off a 200 must branch on the HTTP status. No
in-repo consumer does — `RunVideoPhaseResult` has no `VidingestClient`, MCP or CLI method.
Documented in the per-phase rerun page's Gotchas.

## Follow-ups

None.

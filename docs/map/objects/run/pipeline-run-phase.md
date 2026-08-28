---
type: object
cluster: run
universe: live
status: verified
verified: 2026-08-28
commit: 69f9110
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/domain/PipelineRunPhase.java
---

# PipelineRunPhase

The twelve-value enum that is the pipeline's spine. Ten phases plus two **markers**, `CREATED` and
`DONE`, which are not phases and break anything that treats the enum as an ordered list.

## Why this shape

- **`isOptional()` is one answer to two questions** — "can a run skip this?" and "can the rerun
  endpoint re-execute it alone?" — because they have the same cause: an optional phase consumes the
  persisted `Video` row, while METADATA/DOWNLOAD/PERSIST consume the source URL
  (`PipelineRunPhase.java:31-36`).
- **Order is not in the enum, it is in the registry.** `PipelinePhaseRegistry` fixes execution
  order by constructor injection (`PipelinePhaseRegistry.java:41`), and `PipelinePhase` is a
  **sealed interface** whose `permits` clause lists every phase (`PipelinePhase.java:6`). Sealed
  means tests cannot stub it — they use the real impls with mocked services.

## Shape

Order: `METADATA → DOWNLOAD → PERSIST → TRANSCRIBE → DIARIZE → FRAME_SAMPLE → OCR → FUSE → KNOWLEDGE → CONTEXT`.

- Optional (7): `TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT` — `:33`
- Mandatory (3) + 2 markers: `METADATA, DOWNLOAD, PERSIST`, `CREATED`, `DONE` — `:34`
- Most `vidingest.<phase>.enabled` default **false**: a default local run does
  metadata → download → persist → transcribe → fuse → context.
- Each phase gates itself in `applies(ctx)`; the default is `!ctx.skipped(phase())`. Overrides add
  the enabled check or an upstream dependency (OCR needs FRAME_SAMPLE, DIARIZE needs TRANSCRIBE).

## Connected to

- **owned-by:** nothing — it is an enum, stored as a string on runs, items and events
- **joins:** `PhaseSetConverter` (persists `Set<PipelineRunPhase>` to one column), `SkipPhasesParser` (400s on mandatory or unknown)
- **looks-like-but-is-not:** `LanePhase` in the console — that is this enum **minus** `CREATED` and `DONE` (`core/domain.ts:47`)

## If you change this

Adding a phase touches exactly **five** places, and no more:

- **Hits:** this enum (add the constant, list it in `isOptional()` if skippable), the new `XPhase`
  class, `PipelinePhase`'s `permits` list, the `PipelinePhaseRegistry` constructor, and a config
  class for its `vidingest.<phase>.enabled` toggle. Then `ALL_PHASES` in `core/domain.ts`.
- **Does not hit:** the REST records, MCP tools or CLI options. The per-run opt-out is a
  `Set<PipelineRunPhase>`, so none of them change per phase — that is what replaced six positional
  booleans threaded through 15 files.

## Surfaces

| Surface | Role |
|---|---|
| `PipelinePhaseRegistry` | defines order, resolves `byPhase` |
| `SkipPhasesParser` | validates wire strings; `vidingest-api` cannot import the enum |
| console phase picker | mirrors `ALL_PHASES` / `OPTIONAL_PHASES` / `PHASE_REQUIRES` |

## See

- Source: `.../pipeline/domain/PipelineRunPhase.java`, `.../pipeline/service/phase/PipelinePhaseRegistry.java`
- [docs/vidingest/VidIngest - Per-Phase Rerun.md](../../../vidingest/VidIngest%20-%20Per-Phase%20Rerun.md)

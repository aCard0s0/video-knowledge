---
type: object
cluster: console
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/webapp/src/app/core/domain.ts
---

# domain.ts — the hand-mirrored enums

The server enums the generator refuses to emit. springdoc renders them as bare `string`, so this
file is the console's only typed copy — and the one file a server enum change must not miss.

## Why this shape

- **It is a mirror with no mechanism keeping it in sync.** Nothing fails when the server gains a
  constant and this file does not; the console just stops recognising a value at runtime.
- **`LanePhase` exists because `CREATED` and `DONE` are not phases.** `LANE_PHASES` is `ALL_PHASES`
  minus those two, and `isLanePhase()` must be called before `LANE_PHASES.indexOf` — `failedPhase`
  answers `CREATED` for an item reaped while queued and `DONE` on a clean finish, and `indexOf`
  returns `-1` for both (`domain.ts:36-49`).
- **`PHASE_REQUIRES` encodes the upstream dependencies** the server enforces in `applies(ctx)` —
  OCR needs FRAME_SAMPLE, DIARIZE needs TRANSCRIBE — so the phase picker cannot offer an impossible
  combination (`domain.ts:77`).

## Shape

Mirrors **eight** server enums, not seven:

| Console | Server | Home |
|---|---|---|
| `RUN_STATUSES` | `RunStatus` | `pipeline/domain/` |
| `ALL_PHASES` / `LANE_PHASES` / `OPTIONAL_PHASES` | `PipelineRunPhase` | `pipeline/domain/` |
| `ERROR_CODES` | `PipelineErrorCode` (5 constants) | `pipeline/domain/` |
| `EVENT_TYPES` | `PipelineRunItemEventType` (8) | `pipeline/domain/` |
| `VIDEO_STATUSES` | `VideoStatus` (8) | `videos/domain/` |
| — | `YoutubeChannelStatus` (4) | `youtube/domain/` |
| — | `TranscriptionStatus` | `core/transcription/domain/` |
| `KNOWLEDGE_TYPES` | `KnowledgeUnitType` | **`vidingest-api`**, not the server |

The last row is why [root CLAUDE.md](../../../../CLAUDE.md) says "seven server enums" and this file
carries eight — `KnowledgeUnitType` is in the API module.

## Connected to

- **owned-by:** the eight enum files above. If they disagree, the server wins
- **joins:** `core/lane.ts` (`LanePhase`), the phase picker, every status chip
- **looks-like-but-is-not:** the generated client — this file is deliberately outside `api/generated/`

## If you change this

- **Hits:** every status chip and filter, `core/lane.ts`, the ingest phase picker.
- **Does not hit:** the server. This is a copy — editing it cannot make a value valid.

## Surfaces

| Surface | Role |
|---|---|
| all console features | read |
| a human, after any server enum change | writes |

## See

- Source: `applications/webapp/src/app/core/domain.ts`

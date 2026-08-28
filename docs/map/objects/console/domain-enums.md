---
type: object
cluster: console
universe: live
status: verified
verified: 2026-08-28
commit: 69f9110
entity: applications/webapp/src/app/core/domain.ts
---

# domain.ts — the hand-mirrored enums

The server enums the generator refuses to emit. springdoc renders them as bare `string`, so this
file is the console's only typed copy — and the one file a server enum change must not miss.

## Why this shape

- **It is a mirror with no mechanism keeping it in sync.** Nothing fails when the server gains a
  constant and this file does not; the console just stops recognising a value at runtime.
- **Two of the eight are tracked without being listed.** `YoutubeChannelStatus` and
  `TranscriptionStatus` are only ever *rendered*, never enumerated, so their values live as cases
  in `statusVar` rather than in an exported array. A new constant on either needs a `case`.
- **`LanePhase` exists because `CREATED` and `DONE` are not phases.** `LANE_PHASES` is `ALL_PHASES`
  minus those two, and `isLanePhase()` must be called before `LANE_PHASES.indexOf` — `failedPhase`
  answers `CREATED` for an item reaped while queued and `DONE` on a clean finish, and `indexOf`
  returns `-1` for both (`domain.ts:47-60`).
- **`PHASE_REQUIRES` encodes the upstream dependencies** the server enforces in `applies(ctx)` —
  OCR needs FRAME_SAMPLE, DIARIZE needs TRANSCRIBE — so the phase picker cannot offer an impossible
  combination (`domain.ts:88`).

## Shape

Tracks **eight** server enums; **six** as exported lists.

| Console list | Server enum | Home |
|---|---|---|
| `RUN_STATUSES` | `RunStatus` (5) | `pipeline/domain/` |
| `ALL_PHASES` / `LANE_PHASES` / `OPTIONAL_PHASES` | `PipelineRunPhase` (12) | `pipeline/domain/` |
| `ERROR_CODES` | `PipelineErrorCode` (5) | `pipeline/domain/` |
| `EVENT_TYPES` | `PipelineRunItemEventType` (8) | `pipeline/domain/` |
| `VIDEO_STATUSES` | `VideoStatus` (8) | `videos/domain/` |
| `KNOWLEDGE_TYPES` | `KnowledgeUnitType` (5) | **`vidingest-api`**, not the server |
| *(none — `statusVar` cases)* | `YoutubeChannelStatus` (4) | `youtube/domain/` |
| *(none — `statusVar` cases)* | `TranscriptionStatus` (3) | `core/transcription/domain/` |

`KnowledgeUnitType` living in the API module is exactly how it stayed off the enum list in
[root CLAUDE.md](../../../../CLAUDE.md) while this file mirrored it. It is also the one enum
springdoc *does* emit — twice, per property, as `KnowledgeUnitDtoTypeEnum` and
`SearchKnowledgeHitTypeEnum`. `KNOWLEDGE_TYPES` stays hand-written because picking either would
bind the video-detail filter to one DTO's name, and `Object.values` on a TS `enum` gives
`string[]`, not the literal union.

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

# PR #8 — One skipPhases set, one PipelineRun writer, one preview ranking

**Merged**: 2026-08-25 · **Branch**: `refactor/vidingest-phase-toggle-and-read-model`

Three findings from a structure-and-boundaries review of `applications/vidingest`. The review
found the module layout sound — dependency direction holds, `core/*` never reaches into
`pipeline/controller`, failure translation is already single-sourced through
`PhaseFailureException` — so this is the rest of it.

## Problem

**Adding a phase touched 15 files, 5 of them essentially.** The other 10 were one skip flag
replicated across four modules as the Nth positional boolean: three API records, three
construction sites and two log format strings in `PipelineController`,
`YoutubeChannelCommandService`, two MCP tools, two CLI commands. Every site listed them in the
same order, so inserting a flag mid-list silently reordered the rest and still compiled. The
drift had already happened and nobody noticed: `http/vidingest-server.http` sent two of the six
`@NotNull` flags, so those three requests had been 400ing.

**Run details hydrated entities to read three strings.** `RunDetailsMapper` loaded full `Video`
rows — JSONB `metadata` included — for `videoId`/`channelName`/`title`, exactly the cost the
run-list path shed in #266, and carried a verbatim second copy of that path's preview ranking
with a second exhaustive `VideoStatus` switch.

**`PipelineRun` status had two writers.** `RunLifecycleService` and `RunAggregationService` both
wrote it. Four of `RunLifecycleService`'s five `mark*` methods had no callers at all.

## Change

- `Set<PipelineRunPhase> skipPhases` on `PipelinePhaseContext`; `PipelinePhase.applies` defaults
  to `!ctx.skipped(phase())`. `PipelineRunPhase.isOptional()` + `SkipPhasesParser`.
- `RunDetailsMapper` reads `findRunVideoPreviews`; ranking lives once as
  `RunVideoPreview.PREVIEW_ORDER`.
- `markFailed` moves to `RunAggregationService`; the other four `mark*` and five more
  zero-caller members deleted.

## Decisions

- **Broke the wire format, no deprecation shim.** There is no frontend in the repo and REST is
  reached only through `vidingest-client` → MCP + CLI, so a compat layer would have been net-new
  code existing only to be deleted. External MCP clients built on the old six-boolean tool
  signature do break; the LM Studio doc says so.
- **`isOptional()` serves both skippability and rerunnability.** They are the same question with
  the same cause — an optional phase consumes the persisted video row, METADATA/DOWNLOAD/PERSIST
  consume the URL — and `VideoPhaseRunnerService.RERUNNABLE` was the second copy of the answer.
- **API records carry `Set<String>`, not the enum.** `vidingest-api` must not depend on the
  server, so parsing lands server-side in `SkipPhasesParser`.
- **A bad phase name is a request-level 400, not a per-item `REJECTED`.** Unlike a bad URL, which
  rejects one item of a batch, an unparseable opt-out list makes the whole request meaningless.
  `IllegalArgumentException` already maps to 400 via the existing handler.
- **Validation replaces a structural guarantee.** Six booleans made a mandatory phase unskippable
  by construction; a `Set<String>` off the wire does not. `SkipPhasesParserTest` asserts the
  rejection for *every* non-optional phase rather than the three named today.
- **`FusePhase` gained a per-run opt-out** it previously lacked. Its javadoc argued a separate
  knob would be noise; with a set there is no separate knob to add.
- **CLI keeps the old effective defaults** (`DIARIZE,FRAME_SAMPLE,OCR,KNOWLEDGE`) so a local
  `ingest` behaves as before.

## Deliberately not done

- **Stringly-typed API records left alone.** `RunSummary` is 11 `String`s with `""` for null,
  pushing coercion into the mappers. Ugly, not structural, and changing it is a second wire break.
- **`DownloadService.downloadToDatabase` still duplicates METADATA→DOWNLOAD→PERSIST.** A real
  second ingest path (`processMetadata` vs `createVideoFromMetadata`), but the disk-only endpoint
  needs it and merging would grow code.
- **`VideoRepository.findByPipelineRun_IdIn` kept** despite zero production callers: its only
  reference is `RunSummaryPageServiceTest` asserting it is *never* called, which is the #266
  tripwire. `findAllByPipelineRun_Id` did go — this PR removed its last caller.
- **`PipelinePhaseContext` stays a mutable carrier.** No finding motivated changing it.

## Follow-ups

- Still no CI. Nothing enforces the 344 tests but a local run.

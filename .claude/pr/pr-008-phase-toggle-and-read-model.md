# PR #8 — One skipPhases set, one PipelineRun writer, one preview ranking

**Merged**: 2026-08-26 · **Branch**: `refactor/vidingest-phase-toggle-and-read-model`

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
- `lease_owner`/`lease_expires_at` on run items (`RunItemLeaseService`, heartbeated from
  `PipelineService`), so reconcilers can tell a live item from an abandoned one across
  instances rather than only within this JVM.

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
- **Both liveness answers are kept, neither is redundant.** The in-flight set cannot see other
  instances but stays right if the lease heartbeat stalls; the lease sees every instance but goes
  stale if it does. An item is reaped only when neither claims it.
- **Lease renewal is scoped by owner**, so a heartbeat can never extend a lease another instance
  took over.
- **A single-instance restart now waits out the lease TTL** instead of failing IN_PROGRESS runs
  immediately. From the reconciler's position a crashed owner and a live peer are genuinely
  indistinguishable, and `StuckItemReconciler` resolves it once the lease lapses.

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
- **No mutual exclusion at lease acquire.** Two workers on one item needs an operator retry, and
  retry already refuses anything not FAILED. The lease closes the path that made a live item
  *look* FAILED, which is where the hole actually was.
- **Lease owner is `pid@host`, not a generated UUID.** It survives nothing, which is the point:
  a restarted process must not inherit its predecessor's claims. No override property — Docker
  and k8s both hand out distinct hostnames, so the collision it would guard against needs
  identical pid *and* host.

## What live verification changed

The test suite covered the new shape and nothing else. Driving a real server, CLI and MCP client
found three things it structurally could not:

- **An old six-boolean body was silently accepted with its flags dropped** — a 202 and a run that
  executed every phase the caller asked to skip. Jackson ignores unknown properties by default,
  and every test sent the new shape, so nothing failed. Fixed by
  `spring.jackson.deserialization.fail-on-unknown-properties=true` plus an
  `HttpMessageNotReadableException` handler, since the advice's `Exception` catch-all would
  otherwise have answered 500 to a malformed body. Strictness is global, deliberately: a field
  the server does not understand should never look like it was applied.
- **The CLI option is `--skipPhases`, not `--skip-phases`** as the docs claimed. Spring Shell
  derives the name from the parameter, matching the CLI's existing `--dryRun`. `IngestCommandsTest`
  calls the method directly, so option naming was never exercised.
- **MCP marked `skipPhases` required** while its own description said it could be omitted. Fixed
  with `@McpToolParam(required = false)`.

A fourth thing was learned rather than fixed: Spring Boot 4 binds request bodies with Jackson 3,
so the Jackson 2 `ObjectMapper` bean in `JacksonConfig` does not govern the REST edge. The first
attempt at the strictness fix went into that bean and did nothing.

## What the over-engineering pass cut

A `/ponytail-review` over this branch's own diff found ~70 lines that did not need to exist:

- **`RunItemLeaseHeartbeat` was a class built to break a cycle that did not exist.** Its javadoc
  claimed scheduling on either collaborator would make them depend on each other;
  `RunItemLeaseService` never referenced `PipelineService`, which already injects the lease
  service and owns the in-flight set. The schedule is now a method on `PipelineService`.
- **`VideoPhaseRunnerService` re-implemented `SkipPhasesParser`** — same normalisation, same
  `isOptional()` check, same "Allowed:" message. Deleting `RERUNNABLE` removed the duplicated
  *set* and left the duplicated *parse*. Both now go through `SkipPhasesParser.parseOptional`,
  whose message lost its `skipPhases`-specific wording so it reads correctly from both callers.
- **An index on `lease_expires_at` no query uses.** The reconciler sweep leads with
  `(status, phase_updated_at)`, the run-level check with `pipeline_run_id`, and
  acquire/renew/release go by primary key.
- **`vidingest.lease.owner`**, an override nothing sets, guarding a collision that needs
  identical pid *and* hostname.
- **Two of four conditions in `PipelinePhaseContext.skipped` were dead** — the parser guarantees
  only optional phases enter the set, and every constructor passes a non-null one.

`RunItemLeaseService.owner()` survived the pass: the review called it heartbeat-only, but
`RunItemLeaseIntegrationTest` uses it to assert the row is stamped with *this* instance's
identity, which is the contract.

## Follow-ups

- Still no CI. Nothing enforces the 352 tests but a local run. Declined for now.

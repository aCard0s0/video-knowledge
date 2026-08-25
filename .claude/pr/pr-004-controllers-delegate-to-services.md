# PR #4 — Move query logic out of the artifact controllers

**Merged**: 2026-08-25 · **Branch**: `refactor/controllers-delegate-to-services` · **Merge commit**: `4595ab6`

Structural only — no behaviour change.

## Problem

Five controllers delegate to services; three reached for repositories directly.

- **`VideoMultimodalArtifactsController`** — 3 repositories + 2 mappers, with the
  `fromSeconds`/`toSeconds` window written **twice**: as a stream `.filter` for the unpaged
  endpoint and as JPQL for the paged one. One rule, two implementations. (Correcting the review
  that prompted this: they had not actually diverged on null handling — but nothing made them
  agree.)
- **`SpeakerController`** — display-name normalisation, the segment-count join and an existence
  check inline, with `@Transactional` on the handler method putting the transaction boundary on
  the HTTP request. It threw Spring's `ResponseStatusException` for a domain condition.
- **`KnowledgeController`** — clamped the search limit with a `MAX_SEARCH_LIMIT = 50` that
  `SemanticKnowledgeSearchService` already applied internally. Same magic number, two places.

## Change

Four new services — `MultimodalTimelineQueryService`, `OcrQueryService`, `SpeakerService`,
`KnowledgeQueryService`. Controllers keep parameter binding and OpenAPI annotations, nothing
else. Dependencies: 5 → 2, 4 → 1, 5 → 3.

## Decisions

- **The unpaged timeline runs the same JPQL as the paged one** via `Pageable.unpaged()`, so the
  window has exactly one definition.
- **`SpeakerNotFoundException` replaces `ResponseStatusException`**, mapping to 404 through the
  handler like every other not-found.
- **All four services use a new `VideoQueryService.ensureExists`** rather than each taking a
  `VideoRepository` — which also deleted three copies of a private `ensureVideoExists`.
- **`@Transactional` moves off the web layer.**
- **WebMvc slices import the real services** rather than mocking them, so their behavioural
  assertions still run end to end (`SpeakerControllerWebMvcTest` needed a 2-line `@Import`).

## Deliberately not done

- No doc updates — no page carries a code pointer to these classes and the REST contract is
  untouched.
- No change to limit semantics (`null` → 10, clamped to [1, 50]). One cosmetic difference: the
  unknown-speaker 404 `title` is now `"Not found"`, matching the rest of the API rather than
  Spring's `"Not Found"`.

## Follow-ups

None.

---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 69f9110
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/knowledge/domain/KnowledgeUnit.java
---

# KnowledgeUnit

LLM-extracted knowledge from the fused segments: a procedure, entity, topic, summary, claim or question, with
a time span and a 1536-dim embedding.

## Why this shape

- **KNOWLEDGE fails on any failed batch** — unlike [OcrResult](ocr-result.md), which skips. A batch
  is ~40 segments of coverage, not one frame; salvaging the rest would silently narrow the
  extraction and nothing downstream could tell.
- **Its transaction covers only the wipe and the repopulate**, taken after the loop, for the same
  reason as OCR: never hold a connection across an LLM call.
- **`KnowledgeUnitType` lives in `vidingest-api`, not the server** —
  `.../api/knowledge/KnowledgeUnitType.java`. The console mirrors it as `KNOWLEDGE_TYPES`
  (`core/domain.ts:130`), and living in the API module is exactly how it stayed off the enum list
  in [root CLAUDE.md](../../../../CLAUDE.md) — a list that said "server enums". That list now names
  it; see [domain.ts enums](../console/domain-enums.md) for which of the eight are arrays and which
  are only `statusVar` cases.
- **The LLM caller is not Ollama-only.** `vidingest.knowledge.provider` picks `ollama` or
  `openai-compatible`, so LM Studio, llama.cpp, mlx-lm or vLLM is a property change.

## Shape

- `vidingest_knowledge_units` — `:33`; `video` FK `CASCADE`, `type` `KnowledgeUnitType` (32),
  `title` (512), `content`, `metadata` `jsonb`, `startSeconds`/`endSeconds`,
  `embedding` `vector(1536)` — `:47`–`:76`
- Indexes `(video_id, type)` and HNSW on `embedding` — `004-knowledge.sql:48-49`

- **`metadata.prompt_version` is now read, not just written.** `GET /api/v1/knowledge/stale`
  reports videos whose units came from an older prompt than the server sends, because a prompt
  upgrade changes what extraction means (v2 → v3: 4.3 of 19 stated rules recovered, against 14.7).
  It reports rather than re-extracts — one video is minutes of LLM time — and pairs with the
  per-video regenerate. **The first query in the schema that reads JSONB by content**, and
  deliberately still without a GIN index on `metadata`.
- **`content` is post-processed after the LLM returns it.** `stripEmptySlotLines` removes rule-kind
  lines the model filled in with "None specified", and `stripMetaOpening` turns "The video explains
  X" into "X". Both are in code rather than the prompt because instructing the model measured
  *worse* — see `KnowledgeExtractionPrompt`'s noise-floor note. A test asserting on stored content
  must expect the cleaned form.

## Connected to

- **owned-by:** [Video](../media/video.md) (`CASCADE`)
- **joins:** consumes [MultimodalSegment](multimodal-segment.md)
- **looks-like-but-is-not:** [ContextChunk](context-chunk.md). Same embedding width, different job —
  chunks are for retrieval over the whole video, units are extracted assertions.

## If you change this

- **Hits:** `KnowledgePhase` (batching + failure policy), the knowledge chat client under
  `core/knowledge/client/` and its two provider packages, `KnowledgeController` (`/knowledge/search`,
  `/videos/{id}/knowledge`, `/regenerate`), `KNOWLEDGE_TYPES` in `core/domain.ts`.
- **Does not hit:** search over chunks. `/search` is [ContextChunk](context-chunk.md); knowledge
  search is its own endpoint over its own HNSW index.

## Surfaces

| Surface | Role |
|---|---|
| `KnowledgePhase` | writes (`vidingest.knowledge.enabled`, default false) |
| `llm` compose service (`:11434`) | produces the extraction |
| console video detail knowledge pane | reads |

## See

- Source: `.../core/knowledge/`
- [docs/vidingest/VidIngest - Knowledge Extraction.md](../../../vidingest/VidIngest%20-%20Knowledge%20Extraction.md)

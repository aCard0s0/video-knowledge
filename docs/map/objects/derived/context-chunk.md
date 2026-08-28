---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/search/domain/ContextChunk.java
---

# ContextChunk

CONTEXT output: the video's content cut into ordered chunks with a 1536-dim embedding each. This is
what `/api/v1/search` searches.

## Why this shape

- **The embedding provider has three implementations, one of which does nothing.**
  `vidingest.search.embeddings.provider` picks `ollama`, `openai-compatible`, or `disabled` — the
  `Disabled*` floor is what integration tests run on, so the schema must tolerate a null embedding.
- **1536 is a hard contract with the model.** The embed model must be pulled before the first run
  or CONTEXT fails; a different width means a new column type, not a config change.
- **`UNIQUE (video_id, chunk_index)`** makes re-running CONTEXT idempotent; no index on `video_id`
  alone, and the HNSW index is the point of the table (`005-search.sql:16`, `:19-21`).

## Shape

- `vidingest_context_chunks` — `:17`; `video` FK `CASCADE`, `chunkIndex`, `content`,
  `embedding` `vector(1536)` — `:31`–`:40`
- HNSW index on `embedding` — `005-search.sql:21`

## Connected to

- **owned-by:** [Video](../media/video.md) (`CASCADE`)
- **joins:** built from [MultimodalSegment](multimodal-segment.md)
- **looks-like-but-is-not:** [KnowledgeUnit](knowledge-unit.md) — same vector width, different index, different endpoint

## If you change this

- **Hits:** `ContextPhase`, `OllamaEmbeddingsClient` and its openai-compatible sibling,
  `SearchController` (`/api/v1/search`), `/videos/{id}/context/regenerate`, and the
  `pgvector/pgvector:pg17` requirement — `vector` is the only extension the schema creates.
- **Does not hit:** knowledge search. Separate table, separate index, separate endpoint.

## Surfaces

| Surface | Role |
|---|---|
| `ContextPhase` | writes (wipe + repopulate) |
| `llm` compose service (`:11434`) | produces embeddings |
| `SearchController`, MCP tools | read |

## See

- Source: `.../search/`

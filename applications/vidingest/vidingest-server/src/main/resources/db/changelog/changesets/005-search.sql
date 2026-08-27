-- liquibase formatted sql
-- changeset vidingest:005-search

-- The retrieval scope: embedded context chunks behind semantic search. Its own file because it is
-- the only table the search feature owns, and the embedding dimension here is tied to
-- vidingest.search.embeddings.expected-dimensions rather than to any extraction phase.

CREATE TABLE vidingest_context_chunks
(
    id          UUID PRIMARY KEY,
    video_id    UUID         NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    embedding   VECTOR(1536),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, chunk_index)
);

-- No index on video_id alone: leftmost column of the unique constraint above. The HNSW index is
-- partial because a chunk with no embedding is never a search candidate.
CREATE INDEX idx_vidingest_chunks_embedding
    ON vidingest_context_chunks
        USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

--rollback DROP TABLE IF EXISTS vidingest_context_chunks CASCADE;

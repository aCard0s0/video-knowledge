-- liquibase formatted sql
-- changeset vidingest:006-index-cleanup

-- Ten indexes that cost writes and serve nothing.
--
-- Seven are leftmost-prefix subsets of an index that already exists, so every query they served
-- falls through to the wider one. Verified by dropping all seven against a copy of production data
-- and re-planning each query with enable_seqscan=off — none lost its index:
--
--   idx_vidingest_chunks_video_id            -> vidingest_context_chunks_video_id_chunk_index_key
--   idx_vidingest_video_frames_video_id      -> idx_vidingest_video_frames_video_time
--   idx_vidingest_multimodal_video_id        -> idx_vidingest_multimodal_video_time
--   idx_vidingest_speakers_video_id          -> vidingest_speakers_video_id_label_key
--   idx_vidingest_segments_transcription_id  -> idx_vidingest_segments_transcription_id_start
--   idx_vidingest_knowledge_units_video_id   -> idx_vidingest_knowledge_units_video_type
--   idx_vidingest_videos_source              -> vidingest_videos_source_source_video_id_key
--
-- pg_stat_user_indexes makes the narrow ones look used (speakers_video_id had 29 scans against 0
-- on its unique key). That only says the planner prefers the narrower of two interchangeable
-- indexes, not that both are needed. 002-youtube-channels.sql already applies this rule to
-- channel_videos; 001 and 003 did not.
--
-- The other three are GIN indexes on JSONB that nothing queries by content. There is no @>, no ->>
-- and no jsonb path anywhere in the codebase — metadata is written and read whole. The videos one
-- had reached 1656 kB against three rows, the largest index in the database, and it is rebuilt on
-- every metadata write.
--
-- Add a GIN index back the day a query actually filters on JSONB, naming that query in
-- whichever migration re-adds it.

DROP INDEX IF EXISTS idx_vidingest_chunks_video_id;
DROP INDEX IF EXISTS idx_vidingest_video_frames_video_id;
DROP INDEX IF EXISTS idx_vidingest_multimodal_video_id;
DROP INDEX IF EXISTS idx_vidingest_speakers_video_id;
DROP INDEX IF EXISTS idx_vidingest_segments_transcription_id;
DROP INDEX IF EXISTS idx_vidingest_knowledge_units_video_id;
DROP INDEX IF EXISTS idx_vidingest_videos_source;

DROP INDEX IF EXISTS idx_vidingest_videos_metadata;
DROP INDEX IF EXISTS idx_vidingest_knowledge_units_metadata;
DROP INDEX IF EXISTS idx_vidingest_youtube_channel_videos_metadata;

--rollback CREATE INDEX idx_vidingest_chunks_video_id ON vidingest_context_chunks (video_id);
--rollback CREATE INDEX idx_vidingest_video_frames_video_id ON vidingest_video_frames (video_id);
--rollback CREATE INDEX idx_vidingest_multimodal_video_id ON vidingest_multimodal_segments (video_id);
--rollback CREATE INDEX idx_vidingest_speakers_video_id ON vidingest_speakers (video_id);
--rollback CREATE INDEX idx_vidingest_segments_transcription_id ON vidingest_transcription_segments (transcription_id);
--rollback CREATE INDEX idx_vidingest_knowledge_units_video_id ON vidingest_knowledge_units (video_id);
--rollback CREATE INDEX idx_vidingest_videos_source ON vidingest_videos (source);
--rollback CREATE INDEX idx_vidingest_videos_metadata ON vidingest_videos USING GIN (metadata);
--rollback CREATE INDEX idx_vidingest_knowledge_units_metadata ON vidingest_knowledge_units USING GIN (metadata);
--rollback CREATE INDEX idx_vidingest_youtube_channel_videos_metadata ON vidingest_youtube_channel_videos USING GIN (metadata);

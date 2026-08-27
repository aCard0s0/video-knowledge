-- liquibase formatted sql
-- changeset vidingest:007-speaker-labels

-- multimodal_segments referenced speakers by UUID in a bare array, so nothing enforced that the
-- rows still existed. DIARIZE is wipe-then-repopulate: re-running it alone via
-- POST /api/v1/videos/{id}/phases/DIARIZE/run deletes every speaker and recreates it with a NEW
-- uuid, while speaker_ids kept the old ones. transcription_segments.speaker_id survives that
-- correctly through its ON DELETE SET NULL FK; the array had no FK, so postgres did nothing and
-- the timeline endpoint served ids pointing at deleted rows until FUSE happened to re-run.
--
-- The label is the fix, not a join table. UNIQUE (video_id, label) already makes it the natural
-- key, and a re-run recreates the same labels ('SPEAKER_00', ...) — so the reference cannot dangle
-- by construction and needs no foreign key to stay honest. A join table would restore integrity
-- but costs a table, two indexes and a mapper for a value that is at most a handful of names,
-- always read whole with its segment.
--
-- Existing rows convert through the speakers they still point at. Where an id had already been
-- orphaned by a DIARIZE re-run, array_agg returns no row and the column lands NULL, which is the
-- honest answer: that segment's speakers are genuinely unknown.

ALTER TABLE vidingest_multimodal_segments
    ADD COLUMN speaker_labels TEXT[];

UPDATE vidingest_multimodal_segments m
SET speaker_labels = (SELECT array_agg(s.label ORDER BY s.label)
                      FROM vidingest_speakers s
                      WHERE s.id = ANY (m.speaker_ids))
WHERE m.speaker_ids IS NOT NULL;

ALTER TABLE vidingest_multimodal_segments
    DROP COLUMN speaker_ids;

--rollback ALTER TABLE vidingest_multimodal_segments ADD COLUMN speaker_ids UUID[];
--rollback UPDATE vidingest_multimodal_segments m SET speaker_ids = (SELECT array_agg(s.id) FROM vidingest_speakers s WHERE s.video_id = m.video_id AND s.label = ANY (m.speaker_labels)) WHERE m.speaker_labels IS NOT NULL;
--rollback ALTER TABLE vidingest_multimodal_segments DROP COLUMN speaker_labels;

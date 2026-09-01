# Noun index

19 cards. Scan this; open one. `verified` cards were checked against `0a40fa2` on 2026-08-28,
except [Connection](runtime/connection.md), added 2026-08-29.

## run — execution state

| Noun | One line | Status |
|---|---|---|
| [PipelineRun](run/pipeline-run.md) | one submission of one or more URLs; holds no work | verified |
| [PipelineRunItem](run/pipeline-run-item.md) | one URL; where the pipeline runs, fails, and holds its lease | verified |
| [PipelineRunItemEvent](run/pipeline-run-item-event.md) | append-only audit; the only record of which phases ran | verified |
| [PipelineRunPhase](run/pipeline-run-phase.md) | the 12-value spine — 10 phases plus 2 markers | verified |

## media — what is being ingested

| Noun | One line | Status |
|---|---|---|
| [Video](media/video.md) | the persisted row every optional phase consumes | verified |
| [YoutubeChannel](media/youtube-channel.md) | a watched channel URL; syncing lists, it does not ingest | verified |
| [YoutubeChannelVideo](media/youtube-channel-video.md) | a candidate seen on a channel; no FK to Video | verified |

## derived — what the phases produce

| Noun | One line | Status |
|---|---|---|
| [Transcription](derived/transcription.md) | whisper output, parent + timed segments | verified |
| [Speaker](derived/speaker.md) | one diarized voice; `label` is the natural key, not the id | verified |
| [VideoFrame](derived/video-frame.md) | one sampled still on disk; OCR's input | verified |
| [OcrResult](derived/ocr-result.md) | text read off one frame; skips a bad frame, unlike KNOWLEDGE | verified |
| [MultimodalSegment](derived/multimodal-segment.md) | FUSE output; what KNOWLEDGE and CONTEXT actually read | verified |
| [KnowledgeUnit](derived/knowledge-unit.md) | LLM-extracted entity/topic/summary/claim/question + embedding | verified |
| [ContextChunk](derived/context-chunk.md) | CONTEXT output; what `/api/v1/search` searches | verified |

## runtime — where the server reaches its dependencies

| Noun | One line | Status |
|---|---|---|
| [Connection](runtime/connection.md) | the six runtimes VidIngest drives, editable without a restart; a row is an override, not the config | verified |

## console — why the UI shows that

| Noun | One line | Status |
|---|---|---|
| [Generated API client](console/generated-client.md) | the whole HTTP layer; regenerated, never edited | verified |
| [domain.ts enums](console/domain-enums.md) | eight server enums mirrored by hand, nothing keeps them in sync | verified |
| [ApiFailure](console/api-failure.md) | the error envelope, and which failure a screen shows | verified |
| [watchRun](console/run-watch.md) | run + audit tail + lanes, shared by the two lane screens | verified |

## Deliberately not cards

- **Every DTO and mapper.** They follow their entity; the card names the mapper under *Hits*.
- **`libraries/common-*`.** Vendored shared libs — dependencies, not app code. Change them only
  when the shared behaviour is wrong.
- **Config classes.** One per phase toggle, all the same shape; [PipelineRunPhase](run/pipeline-run-phase.md) names the pattern.

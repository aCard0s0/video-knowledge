---
type: object
cluster: runtime
universe: live
status: verified
verified: 2026-09-01
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/connections/domain/Connection.java
---

# Connection

Where the server reaches one runtime — an LLM or a sidecar — and the only row in the schema that
is configuration rather than data. Six names, fixed:
`EMBEDDINGS`, `KNOWLEDGE`, `TRANSCRIPTION`, `DIARIZATION`, `FRAME_SAMPLE`, `OCR`.

## Why this shape

- **A row is an override, not the configuration.** Absent, the environment-bound
  `@ConfigurationProperties` value applies. That is what makes `DELETE` mean "back to what `.env`
  said" — and it only works because `ConnectionSettingsService` snapshots the environment values
  at startup *before* applying rows. Nothing else in the process remembers them.
- **The primary key is the name, not a UUID.** The set is closed and named, so a surrogate id would
  add a join key nothing joins on and let two rows claim the same connection.
- **It applies on `ApplicationReadyEvent`, not `@PostConstruct`.** The table does not exist at
  bean-init time — Liquibase has not run — and the first query fails with
  `relation "vidingest_connections" does not exist`.
- **`api_key` is write-only and plaintext.** The API returns `hasApiKey`, never the key; an absent
  `apiKey` on update keeps the stored one and `""` clears it, because a console that cannot read
  the key back could not otherwise save any other field without wiping it. `ConnectionProbeService`
  sends it too — a "test" that 401s while the phase works is a worse signal than no test.
- **`openai` is a provider value, not a synonym for `openai-compatible`.** Accepted on the three
  LLM connections. Same client and same endpoints; only the knowledge-chat body differs, because
  api.openai.com 400s on `max_tokens`, on a non-default `temperature` and on a `strict: true`
  schema that is not strict-compliant. The dialect is chosen per call inside
  `OpenAiCompatibleKnowledgeChatClient`, not by a second bean.
- **Timeouts are deliberately absent.** Each transport consumes them once when its request factory
  is built, so a value served here would not be the one in use.
- **`FRAME_SAMPLE` is a connection with no connection.** Local ffmpeg: no base URL, no model,
  nothing to probe. It is on this API because what the settings screen manages is which enrichment
  the deployment performs, and frame sampling was the one toggle with no home — which made OCR's
  toggle beside it a trap, since `OcrPhase` has no input without frames. `Binding.hasBaseUrl` is
  what keeps that from becoming three `if (name == FRAME_SAMPLE)` branches: it gates the URL
  validation, the stored column and the probe in one place.

## Shape

- `vidingest_connections` — `Connection.java:38`; `name` PK (`ConnectionName`, `STRING`),
  `provider`, `base_url` (nullable since `009-connections-nullable-base-url.sql`), `model`,
  `api_key`, `enabled`, `updated_at`
- No indexes and no `created_at`: at most six rows, read whole at startup, by PK otherwise
  (`008-connections.sql`)
- The bridge to the config beans is `ConnectionSettingsService`'s `Binding` record — one reader and
  one writer per connection over `ConnectionValues`, so the awkward parts stay in two lambdas:
  `ConnectionSettingsService.java:66`

## Connected to

- **owns:** nothing — it mutates `@ConfigurationProperties` singletons in place
- **owned-by:** nothing; no FK in either direction
- **joins:** nothing
- **looks-like-but-is-not:** an entity. It is a settings row, and the only one in the schema — every
  other `@ConfigurationProperties` value is still environment-only and needs a restart.

## If you change this

- **Hits:** `VideoSearchConfig`, `KnowledgeExtractionConfig`, `TranscriptionClientProperties`,
  `DiarizationConfig`, `FrameSamplingConfig`, `OcrConfig` (the six beans it writes); the four
  routers that read `provider` per call; the console `features/settings/`; the generated client.
- **Adding a provider value** means one entry in that router's `SUPPORTED_PROVIDERS` — the switch
  and the console dropdown both follow from it, since `supportedProviders` is served per row.
  Nothing on the frontend changes.
- **Adding a connection** means: a `ConnectionName` constant, a `Binding` in the service
  constructor, and nothing else — the controller, the DTOs and the console iterate the enum, and
  the three `supports*` flags decide which controls render.
- **Does not hit:** the schema of anything else, or any phase's logic. A connection decides *where*
  a phase calls, never *what* it does.

## The mechanism, and how to break it

Runtime editing works because the config beans are mutable singletons **and every client resolves
its base URL per call**. The four `RestClient` beans therefore carry **no** `.baseUrl(...)`:
`TranscriptionRestClientConfig`, `DiarizationRestClientConfig`, `OcrRestClientConfig`,
`KnowledgeChatRestClientConfig`. Adding one back re-pins the URL to startup and the settings API
goes quiet — no error, the writes just stop reaching the wire.

## Surfaces

| Surface | Role |
|---|---|
| `ConnectionsController` | reads / writes / probes |
| `ConnectionSettingsService` | the only writer of the config beans |
| `ConnectionProbeService` | reads (never writes), key included; `POST .../test` always answers 200 |
| console `features/settings/` | reads / writes |
| every phase client | reads its base URL per call |

## See

- Source: `.../connections/`
- Changeset: `008-connections.sql`
- Doc: [Config and Runtime](../../../vidingest/VidIngest%20-%20Config%20and%20Runtime.md#connections-api-runtime-editable)

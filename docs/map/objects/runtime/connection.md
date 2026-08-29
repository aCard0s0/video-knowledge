---
type: object
cluster: runtime
universe: live
status: verified
verified: 2026-08-29
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/connections/domain/Connection.java
---

# Connection

Where the server reaches one external runtime — an LLM or a sidecar — and the only row in the
schema that is configuration rather than data. Five names, fixed:
`EMBEDDINGS`, `KNOWLEDGE`, `TRANSCRIPTION`, `DIARIZATION`, `OCR`.

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
  the key back could not otherwise save any other field without wiping it.
- **Timeouts are deliberately absent.** Each transport consumes them once when its request factory
  is built, so a value served here would not be the one in use.

## Shape

- `vidingest_connections` — `Connection.java:38`; `name` PK (`ConnectionName`, `STRING`),
  `provider`, `base_url NOT NULL`, `model`, `api_key`, `enabled`, `updated_at` — `:40`–`:66`
- No indexes and no `created_at`: at most five rows, read whole at startup, by PK otherwise
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
  `DiarizationConfig`, `OcrConfig` (the five beans it writes); the four routers that read
  `provider` per call; the console `features/settings/`; the generated client.
- **Adding a connection** means: a `ConnectionName` constant, a `Binding` in the service
  constructor, and nothing else — the controller, the DTOs and the console iterate the enum.
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
| `ConnectionProbeService` | reads (never writes); `POST .../test` always answers 200 |
| console `features/settings/` | reads / writes |
| every phase client | reads its base URL per call |

## See

- Source: `.../connections/`
- Changeset: `008-connections.sql`
- Doc: [Config and Runtime](../../../vidingest/VidIngest%20-%20Config%20and%20Runtime.md#connections-api-runtime-editable)

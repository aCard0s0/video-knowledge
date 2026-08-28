---
type: object
cluster: console
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/webapp/src/app/api/generated/
---

# Generated API client

The console's entire HTTP layer, produced by openapi-generator 7 from a snapshot of the live spec.
**Never hand-written, never hand-edited.**

## Why this shape

- **`--type-mappings=set=Array` is load-bearing.** A Java `Set` field (`skipPhases`) becomes
  `uniqueItems: true`, which generates `Set<string>` — and `JSON.stringify(new Set(['OCR']))` is
  `"{}"`. The field silently reaches the server as an object and the request 400s. Keep the flag on
  every regeneration (`applications/webapp/package.json`, the `api:codegen` script).
- **Every controller method carries an explicit `@Operation(operationId = …)`.** Without them
  springdoc derives ids from method names, they collide across controllers, and the client gets
  `list1()`, `get2()`, `_delete()`. Adding an endpoint means adding an operationId.
- **The spec is fetched from a running server**, not built offline:
  `curl … /vidingest/v3/api-docs -o openapi/vidingest.json` (`applications/webapp/package.json`, the `api:gen` script). New server code
  must be deployed before the client can know about it.

## Shape

- 13 services under `src/app/api/generated/api/` — `pipelines`, `videos`, `youtube`, `audit`,
  `search`, `knowledge`, `speakers`, `health`, `frame-artifacts`, `video-artifacts`,
  `video-multimodal`, `video-phases`
- Models under `src/app/api/generated/model/`
- Snapshot: `applications/webapp/openapi/vidingest.json` — **commit it with the generated tree**

## Connected to

- **owned-by:** the server's springdoc output; ultimately `VidIngestApiPaths` and the controllers
- **looks-like-but-is-not:** `core/domain.ts`. The generator emits server enums as bare `string`;
  the enums live by hand in `domain.ts` and are **not** part of this tree
- **joins:** `core/api-base.ts`

## If you change this

- **Hits:** nothing to edit here — a change means changing the *server* and re-running
  `npm run api:gen`. Then check `core/domain.ts` for new enum constants and `core/problem.ts` if an
  error body shape moved.
- **Does not hit:** error typing. No operation in the spec documents a 4xx/5xx, so every error body
  generates as `any` — that gap is why [ApiFailure](api-failure.md) exists.

## Surfaces

| Surface | Role |
|---|---|
| every console feature | reads |
| `npm run api:gen` | regenerates, wholesale |

## See

- Source: `applications/webapp/package.json` (`api:gen` / `api:codegen`), `applications/webapp/src/app/api/generated/`
- [processes/regenerate-client.md](../../processes/regenerate-client.md)

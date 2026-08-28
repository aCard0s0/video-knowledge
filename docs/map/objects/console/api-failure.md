---
type: object
cluster: console
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/webapp/src/app/core/problem.ts
---

# ApiFailure — how the console shows a failure

The hand-written error envelope. RFC 9457 `ProblemDetail` typed by hand, plus the rule for **which**
failure a screen shows when several could.

## Why this shape

- **Nothing in the spec documents a 4xx/5xx**, so every generated error body is `any`. This file is
  the only typing there is.
- **`errorCode` is a *pipeline* field, never an HTTP one.** The two are rendered by different
  components and are never merged.
- **`firstFailure(…)` is precedence, not a list** — load failures in order, with the action the
  operator just took in front: `actionFailure() ?? firstFailure(…)` (`problem.ts:92`). Called from
  an injection context, like `syncQueryParams` and `clampPage`.
- **Three server responses do not look like failures and are not treated as such**, and this is
  the file that has to know: `POST /pipelines` answers **400 with a `CreatePipelineRunResponse`
  body**, not a ProblemDetail, when every URL was rejected; `/api/v1/health/ready` answers **503
  carrying the full `ReadinessResult`**, so the failing response *is* the report; and a **202 on
  either retry endpoint does not mean the work was queued** — the same body carries `REJECTED`
  items with a reason.

## Shape

- `ProblemDetail` (`:11`), `ApiFailure` (`:20`), `toApiFailure` (`:35`), `firstFailure` (`:92`), `valueOf` (`:110`)
- Readiness handling lives in `app.ts:191-202` — it says "server unreachable" **only** on status 0.
  Treating any error as unreachable hid the one line naming the broken dependency.

## Connected to

- **joins:** every screen's error branch; `core/paging.ts` (`clampPage` consults a resource only while `hasValue()`)
- **looks-like-but-is-not:** `PipelineErrorCode`. That is a pipeline outcome on a run item; this is transport

## If you change this

- **Hits:** every screen's error branch, `app.ts` readiness banner, the retry flows that must read
  the 202 body instead of discarding it.
- **Does not hit:** the generated client. Typing errors here does not make the spec document them.

## Surfaces

| Surface | Role |
|---|---|
| every feature component | reads |
| `app.ts` | reads readiness out of `HttpErrorResponse.error` |

## See

- Source: `applications/webapp/src/app/core/problem.ts`, `applications/webapp/src/app/app.ts`

# Frontend bootstrap prompt

- **Last reviewed**: 2026-08-27
- **Status**: **historical**. The console was built from this and has moved well past it — see
  [VidIngest - Web UI](vidingest/VidIngest%20-%20Web%20UI.md) for what it actually is now. Kept as
  provenance for how it was generated; nothing reads it.
- **Applies to**: creating the Angular console at `./applications/webapp`
- **Companion**: [frontend-skills.md](frontend-skills.md) — the skill order this prompt enforces

Run Claude Code from the repo root (so `.claude/skills/` loads), start the server, paste the
block below. It answers questions first and writes code second.

---

```
Build the VidIngest web UI — an Angular frontend for this repo's Spring Boot backend, at
./applications/webapp.

## Read before anything

- Path constants (the only source of endpoints):
  applications/vidingest/vidingest-api/src/main/java/com/tradinglabs/vidingest/api/paths/VidIngestApiPaths.java
- DTOs: applications/vidingest/vidingest-api/src/main/java/com/tradinglabs/vidingest/api/**
- Phases: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/domain/PipelineRunPhase.java
- Scope: docs/vidingest/VidIngest.md

Server: http://localhost:8051/vidingest — OpenAPI spec at /v3/api-docs. Generate the typed
client from the spec; do not hand-write it.

The REST API has no SSE and no websocket. Progress is polled. Do not invent a stream.

## Step 1 — interview me. No code yet.

Ask questions, a few at a time, until you can state in three sentences: who uses this, what
they are trying to accomplish, and what makes a session successful. Keep asking until the
answer has no gaps. Cover at least:

- Who operates this and how often — do they watch runs live, or check back after?
- What is the single most common task? What is the most painful one today?
- Which of the 12 phases actually matter to them, and which are noise?
- Is failure diagnosis or search the primary job? That decides the whole layout.
- Which Angular component library, if any, is already mandated?
- Read-only, or can they trigger runs, retries and per-phase reruns from the UI?
- Any existing brand, palette or internal design system to match?

Then propose the screen list and wait for my confirmation. Do not scaffold before I confirm.

## Step 2 — design decisions (no code)

Run the search CLI directly. Report the chosen style, palette and font pairing.

  python3 .claude/skills/ui-ux-pro-max/scripts/search.py "<one line from the interview>" \
    --domain style --stack angular --design-system --project-name vidingest-console --persist

Repeat with --domain color and --domain typography.

## Step 3 — direction

Invoke the frontend-design skill explicitly; its description is thin and will not auto-fire.
Feed it step 2's output as a constraint, not a suggestion. Commit to one direction in a
paragraph.

## Step 4 — build

  ng new webapp --style=scss --routing --ssr=false

Current Angular idiom: standalone components, signals, @if/@for, provideHttpClient(withFetch()),
typed reactive forms. Verify API shapes against current Angular docs rather than memory.

Do NOT use shadcn/ui — React-only, unusable here.

## Step 5 — motion

Skip unless a screen needs it. If so: gsap-core first, gsap-scrolltrigger only for a
scroll-scrubbed timeline. Everything else is a CSS transition. Honour prefers-reduced-motion
with gsap.matchMedia().

## Step 6 — audit, last

  /web-design-guidelines applications/webapp/src/**/*.html

Fix every finding.

## Rules

- Never run ui-ux-pro-max and frontend-design in the same turn.
- No GSAP before step 5.
- Surface API errorCode next to the message. Never collapse it into a generic error.
- CREATED and DONE are run markers, not phases — do not render them as steps.

Start with step 1.
```

---

## Why the interview comes first

The API is fully discoverable from the four paths above, so the agent never needs to ask about
endpoints. What it cannot discover is **who uses the console and what they are trying to do** —
and that single answer decides whether the app is a failure-triage tool or a search tool. Those
are different layouts, not different themes.

Hardcoding a screen list into the prompt skips that question and produces a plausible console
that solves nobody's problem. The interview is the cheap part; rebuilding the layout is not.

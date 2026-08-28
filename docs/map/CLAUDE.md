# VidIngest map — the nouns, the verbs, and what a change hits

A walkable graph of this repo for an agent that will **edit** it. The code is the source of
truth; every card here cites `path:line` and stops. Nothing in this folder is a second spec —
if a card and the code disagree, the code wins and the card is stale.

Subject tree: the whole repo. Orientation that already exists and is **not** duplicated here —
[CLAUDE.md](../../CLAUDE.md) (the why behind the design calls), [docs/Home.md](../Home.md)
(operating it), [.claude/pr/](../../.claude/pr/) (settled trade-offs per PR).

## Where things live

| Folder | What it holds |
|---|---|
| [`objects/`](objects/) | one card per noun, clustered by how an editor asks — [`_index.md`](objects/_index.md) is the one-line list |
| [`processes/`](processes/) | the five movements that actually run |
| [`effects/`](effects/CONTEXT.md) | "I am changing X — open these cards" |
| [`_meta/schema.md`](_meta/schema.md) | closed node types and the naming rule |
| [`_templates/`](_templates/) | blank starters; a new card is a copy |

## Names that collide

| You hear | It is |
|---|---|
| **run** | `PipelineRun` — a *batch* of URLs, one row, one status |
| **item** | `PipelineRunItem` — *one* URL inside that run. The console says "run" for both |
| **phase** | on a run or item, may be `CREATED` or `DONE` — those are markers, **not** phases |
| **video** | the `Video` row. The media file is `Video.filePath`, deleted separately |
| **vidingest** | the 5-module family, *and* the compose service, *and* the URL context path `/vidingest` |
| **console** / **webapp** | `applications/webapp` — Angular, **not** a Maven module |
| **speaker** in a segment | a *label* (`"SPEAKER_00"`), never a `Speaker.id` |

## Universes

- **live** — `applications/`, `libraries/`, `compose/`, `scripts/`, `docs/vidingest/`.
- **leftover** — `com.tradinglabs` groupId, DB `tradingPlatformDB` / user `dealer`, `scripts/tradey.sh`.
  In force, working, named for the trading-platform repo this was carved out of. Renaming is a
  migration, not a tidy-up.
- **ghost** — [design-system/vidingest-console/MASTER.md](../../design-system/vidingest-console/MASTER.md)
  (regenerable CLI output, overridden by `_tokens.scss`);
  [docs/frontend-bootstrap-prompt.md](../frontend-bootstrap-prompt.md) (self-declared historical).
  Do not implement against either.

## Route by the question

| Asking | Go to |
|---|---|
| what is X | [`objects/_index.md`](objects/_index.md) → the card |
| how does X get made | [`processes/CONTEXT.md`](processes/CONTEXT.md) |
| I am changing X, what breaks | [`effects/CONTEXT.md`](effects/CONTEXT.md) |
| why is it built this way | [root CLAUDE.md](../../CLAUDE.md) — not here |
| how do I run it | [docs/Home.md](../Home.md) — not here |

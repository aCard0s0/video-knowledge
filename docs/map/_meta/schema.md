# Schema — the rules of this map

Closed set. A card that is not one of these types does not belong in `docs/map/`.

## Node types

| `type:` | Lives at | Carries |
|---|---|---|
| object | `objects/<cluster>/<slug>.md` | a noun: shape, connections, blast radius |
| process | `processes/<slug>.md` | a verb: Input → Movement → Output, numbered steps |

No third type. A "notes" or "misc" card is a card that has not decided what it is.

## Frontmatter

| Key | Values |
|---|---|
| `type` | `object` \| `process` |
| `cluster` | object only: `run` \| `media` \| `derived` \| `console` |
| `universe` | `live` \| `leftover` \| `ghost` |
| `status` | `stub` \| `verified` \| `stale` |
| `verified` | ISO date. Required when `status: verified` |
| `commit` | short sha the citations were checked against. Required when `status: verified` |
| `entity` | object only: repo-relative path of the owning file |
| `consumes` / `produces` | process only: links to object cards |

`status: verified` without both `verified` and `commit` is a lie. A confident wrong date costs
more than `stale`.

## Naming

- Card files: kebab-case, named for the noun as an editor says it (`pipeline-run-item.md`), not
  for the table (`vidingest_pipeline_run_items`). The table name lives in the card's Shape.
- Clusters are by **how an editor asks**, not by Java package. `ContextChunk` lives in
  `search/` in code and in `derived/` here, because you reach it by asking about CONTEXT output.
- `_meta/` and `_templates/` sort to the top and are about the map, not of the subject.

## Citations

`path:line` relative to repo root. Line numbers rot — that is expected and is what `commit`
is for. A card whose citations no longer resolve is `stale`, not wrong.

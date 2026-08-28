# How to walk this map

One job: answer *what is X* and *what else moves if I change X* without reading the tree.

## The walk

1. [`CLAUDE.md`](CLAUDE.md) — collisions and universes. Read it once per session, not per question.
2. [`objects/_index.md`](objects/_index.md) — find the noun, open **one** card.
3. The card's **See** link — land on source. Verify there, not here.
4. Changing something? [`effects/CONTEXT.md`](effects/CONTEXT.md) *before* the edit, not after.

Never load `objects/` wholesale. The index exists so you do not — that is the whole point of
the catalog. One card is 40–70 lines; the folder is not.

## Card status

`verified` carries a date and a commit. `stale` means the citations were right on that commit and
nobody has re-checked. A card with no citations is a `stub` and is not evidence of anything.

## Known drift in the subject's own docs

None. Four were found and fixed at the source on 2026-08-28 (`0a40fa2`): the changeset
count, the readiness path, and `RunLifecycleService.createPipelineRun` in
[CLAUDE.md](../../CLAUDE.md), plus the "M1 stubs" javadocs on `PipelineRunPhase` and
`PipelinePhaseRegistry`. When you find the next one, fix the doc — this table is a
holding pen, not a home.

## What this map is not

- Not a rewrite of [root CLAUDE.md](../../CLAUDE.md). That holds the *why*; this holds the *what* and the *blast radius*.
- Not as-built behaviour. If a card starts describing what a method does line by line, delete the prose and point at the method.
- Not a place for aspiration. Unwired things are `ghost` and say so.

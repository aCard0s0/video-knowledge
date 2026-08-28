# objects — one card per noun

One job: answer *what is X* in one card, with citations, without opening the tree.

## Inputs
- Working: the noun you were asked about
- Reference: [`_index.md`](_index.md) — the only file you scan
- Reference: [`../_meta/schema.md`](../_meta/schema.md) — frontmatter and naming rules

## Process
1. Find the noun in `_index.md`. One line, one link.
2. Open **that card only**.
3. Follow its **See** link to source if you need more than the card holds.

## Outputs
Nothing. This shelf is read-only during a walk. A new card is a copy of
[`../_templates/object.md`](../_templates/object.md).

## Human check
If you opened more than one card to answer *what is X*, the clustering is wrong — say which two
cards you needed and why, rather than adding a third.

## Clusters

| Cluster | The question that lands here |
|---|---|
| [`run/`](run/) | "why is this run stuck / failed / retrying" |
| [`media/`](media/) | "what is the thing being ingested" |
| [`derived/`](derived/) | "where does transcript / OCR / knowledge / embeddings live" |
| [`console/`](console/) | "why does the UI show that" |

Clusters follow how an editor asks, **not** the Java package. `ContextChunk` is in `search/` in
code and in `derived/` here.

# docs/vidingest — the VidIngest reference shelf

One job: one page per subsystem, each answering *how does this behave and how is it configured*.
Not *why the code is shaped that way* (that is [root CLAUDE.md](../../CLAUDE.md)) and not *what a
change hits* (that is [docs/map](../map/CLAUDE.md)).

## Inputs
- Working: the subsystem you were asked about
- Reference: [../Home.md](../Home.md) — the catalog. It is the only index; this folder has no second one.

## Process
1. Find the page in `../Home.md`.
2. Read it. Every page opens with **Quickstart (for agents)** and closes with **Related pages** —
   the quickstart is the whole answer for most questions.
3. Follow the page's own code pointers to source. Pages cite; source decides.

## Outputs
Nothing. This shelf is read-only during a walk.

## Human check
If answering took more than one page plus its quickstart, say which two pages you needed. That is a
split or a merge waiting to happen — it is how the 80 KB Web UI page was found.

## Frontmatter

Every page carries exactly two fields. Two, because these are the only two anything would query;
the form's own warning is to cut fields nobody reads.

| Field | Values |
|---|---|
| `type` | `overview` \| `reference` \| `guide` \| `findings` |
| `last_reviewed` | ISO date |

**Where the dates came from.** All thirteen pages read **2026-08-29**: every one has been checked
against source — commands, endpoints, properties, columns, enum constants, named test classes, and
every file path each page cites.

Two caveats worth carrying:

- **`Web UI API Findings` is a measured page.** Its response shapes were measured against a running
  server on 2026-08-26 and have *not* been re-measured; only its spec counts were re-checked, against
  the committed `openapi/vidingest.json`. The page states both dates itself.
- **No page is seeded from a commit date any more.** If you add one that way, say so here — that is
  when content last *changed*, not when anyone checked it.

Re-date only when you have actually verified, and prefer an honest old date to a confident wrong
one. `last_reviewed` lives in frontmatter and nowhere else — one home per fact.

## Naming

`VidIngest - <Title>.md`, Title Case, spaces. Not kebab-case, and **not worth changing**: the
eleven original pages carry 56 inbound references from `CLAUDE.md`, `docs/Home.md`, three READMEs
and six `docs/map` cards. A rename is 56 link edits for no navigational gain.

Diagrams are `diagrams/mermaid/*.mmd` sources with rendered `diagrams/svg/*.svg` — regenerate with
`./scripts/regenerate-mermaid-svgs.sh`, never hand-edit the SVG.

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

**Where the dates came from — two kinds, and they are not equal.**

- **Verified against source.** Nine pages read **2026-08-29**: `VidIngest.md`, `Config and Runtime`,
  `Download Pipeline`, `YouTube Channels`, `CLI Commands`, `Test Scenarios`, `Data Model`,
  `Knowledge Extraction` and `MCP with LM Studio`. Their claims were re-checked against the code —
  every command, endpoint, property, column, enum constant and named test class.
- **Author-set.** `Per-Phase Rerun` and the three Web UI pages carry dates their authors set. Nobody
  has re-verified them since.

No page is seeded from a commit date any more. If you add one, seed it that way and say so here —
that is when content last *changed*, not when anyone checked it. Re-date only when you have actually
verified, and prefer an honest old date to a confident wrong one.

`last_reviewed` lives in frontmatter and nowhere else. The three pages that had it in the body had
it removed there — one home per fact.

## Naming

`VidIngest - <Title>.md`, Title Case, spaces. Not kebab-case, and **not worth changing**: the
eleven original pages carry 56 inbound references from `CLAUDE.md`, `docs/Home.md`, three READMEs
and six `docs/map` cards. A rename is 56 link edits for no navigational gain.

Diagrams are `diagrams/mermaid/*.mmd` sources with rendered `diagrams/svg/*.svg` — regenerate with
`./scripts/regenerate-mermaid-svgs.sh`, never hand-edit the SVG.

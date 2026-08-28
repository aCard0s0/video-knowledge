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

- **Verified against source.** `VidIngest.md`, `Config and Runtime`, `Download Pipeline` and
  `YouTube Channels` read **2026-08-29** because their claims were re-checked against the code that
  day. `Per-Phase Rerun` and the three Web UI pages carry dates their authors set.
- **Seeded from the last commit date.** The rest. That is when the content last *changed*, not when
  anyone checked it — an upper bound on freshness, not a promise. Re-date a page only when you have
  actually verified it, and prefer an honest old date to a confident wrong one.

`last_reviewed` lives in frontmatter and nowhere else. The three pages that had it in the body had
it removed there — one home per fact.

The oldest pages are `VidIngest - CLI Commands.md` and `VidIngest - Test Scenarios.md`, both
**2026-08-26**, and both seeded rather than verified.

## Naming

`VidIngest - <Title>.md`, Title Case, spaces. Not kebab-case, and **not worth changing**: the
eleven original pages carry 56 inbound references from `CLAUDE.md`, `docs/Home.md`, three READMEs
and six `docs/map` cards. A rename is 56 link edits for no navigational gain.

Diagrams are `diagrams/mermaid/*.mmd` sources with rendered `diagrams/svg/*.svg` — regenerate with
`./scripts/regenerate-mermaid-svgs.sh`, never hand-edit the SVG.

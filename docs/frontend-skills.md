# Frontend design skills

- **Last reviewed**: 2026-08-26
- **Applies to**: `.claude/skills/` in this repository (project-scoped, not global)
- **Status**: installed and configured for Angular; no `../applications/webapp/` module exists yet

Five agent skills for building UI. This document is the **order** to invoke them in, because
their trigger descriptions overlap and the wrong order costs both context and quality.

---

## Reality check

This repository has no frontend. No `package.json`, no `.ts`/`.tsx`, no `.css`, and
[VidIngest.md](vidingest/VidIngest.md) lists **"Web UI (not part of this
repository)"** under *Out of scope*.

All five skills are therefore inert until a frontend module lands here. They cost nothing
when unused — only `name` + `description` load per session. Undo at any time:

```bash
rm -rf .claude/skills
```

**The stack is Angular** (see [CLAUDE.md](../CLAUDE.md#frontend)). Four of the five skills
carry over — `frontend-design`, `web-design-guidelines` and both GSAP skills are
framework-agnostic, and `ui-ux-pro-max` supports `--stack angular`. **`shadcn` does not**: it is
React-only and is disabled in `.claude/settings.local.json`. Component guidance comes from
Angular Material, PrimeNG or `@spartan-ng/ui` docs instead.

---

## What is installed

| Skill | `SKILL.md` | Invoked cost | Trigger | Slash command |
|---|---|---|---|---|
| `frontend-design` | 55 lines | 55 lines | **Weak description**, no trigger phrases — expect it to under-fire | `/frontend-design` |
| `web-design-guidelines` | 39 lines | 39 + **~2k fetched tokens** | Explicit: *"review my UI"*, *"check accessibility"*, *"audit design"* | `/web-design-guidelines <file-or-pattern>` |
| ~~`shadcn`~~ | 277 lines | — | **Disabled** — React-only, `"off"` in `skillOverrides` | none |
| `gsap-core` | 254 lines | 254 lines | Animation, tweens, easing, reduced-motion | `/gsap-core` |
| `gsap-scrolltrigger` | 296 lines | 296 lines | Scroll-linked, parallax, pinning, scrub | `/gsap-scrolltrigger` |
| `ui-ux-pro-max` | 214 lines + Python CLI | 214 lines + script output | **Very broad** — fires on almost any UI request | `/ui-ux-pro-max` |

**858 lines of SKILL.md available** (1,135 installed, minus the 277 in the disabled `shadcn`).
Two facts drive everything below:

1. An invoked skill's body enters the conversation as one message and **stays for the rest of
   the session**. Claude Code does not re-read the file. Order is therefore cumulative cost.
2. `web-design-guidelines` is a 39-line shim that **WebFetches its rules at review time** from
   `vercel-labs/web-interface-guidelines` (190 lines, ~2k tokens). Always current, but it
   **needs network egress when you run the review**.

`ui-ux-pro-max` ships real data, verified on install — `core.py` 44K, `design_system.py` 72K,
`phosphor-icons-upstream.json` 808K. Its `search.py` runs on the system Python 3.9.6.

---

## The problem this order solves

`ui-ux-pro-max`'s description claims *"designing, building, reviewing, or fixing interfaces,
including pages, components, design systems, accessibility, interaction, responsive layout,
typography, color"*. That is a superset of both `frontend-design` and `web-design-guidelines`.

Say *"build me a settings page"* with all five installed and three skills fire at once, each
with a different opinion about what to decide first. The order below is not a style
preference — it is the thing that stops them fighting.

**Two rules:**

- **`ui-ux-pro-max` is a lookup table, not a generator.** It runs *before* `frontend-design`,
  hands over palette and type candidates, and stops. `frontend-design`'s job is to *commit* to
  one. Reverse them and the committed direction gets relitigated against 192 palettes.
- **Never invoke GSAP before phase 3.** 550 lines of animation API sitting in context while
  you are still choosing colors buys nothing.

---

## Order of use

### Phase 0 — Decide (no code)

`ui-ux-pro-max`, as a CLI. Do not invoke the skill body yet; just run the script.

```bash
python3 .claude/skills/ui-ux-pro-max/scripts/search.py "video ingestion console with transcript search" \
  --domain style --stack angular --design-system --project-name vidingest --persist
```

`--persist` writes the chosen system to disk so later phases reference a decision rather than
re-deriving one. Then narrow:

```bash
python3 .claude/skills/ui-ux-pro-max/scripts/search.py "dense data console, dark mode" --domain color -n 5
```

```bash
python3 .claude/skills/ui-ux-pro-max/scripts/search.py "monospace numerals, long transcript reading" --domain typography -n 5
```

Useful domains: `style`, `color`, `typography`, `chart`, `ux`, `icons`, `gsap`, `react`.
Dials: `--variance 1-10`, `--motion 1-10`, `--density 1-10`.

**Output:** a palette, a font pairing, a style name. Three lines of decision. **Cost: zero
context** — script output only, no skill body loaded.

### Phase 1 — Direction

```
/frontend-design
```

Then state the brief *and* the phase-0 output:

> Build the VidIngest web UI shell. Direction from ui-ux-pro-max: <style>, palette <hexes>,
> type <pairing>. Dense data tool, dark-first, operators watch it for hours. No gradients.

The skill forces four answers before CSS — purpose, tone, constraints, differentiation — then
commits to one extreme. Feeding it phase 0's output turns "pick an extreme" into "execute this
extreme", which is what you want on a real product rather than a landing page.

Its description is thin, so **invoke it explicitly**. Do not rely on auto-trigger.

**Cost: 55 lines.**

### Phase 2 — Build

```bash
ng new webapp --style=scss --routing --ssr=false
```

Pick **one** Angular component library and stay with it — Angular Material, PrimeNG or
`@spartan-ng/ui`. No skill covers this; pull current API shapes from the library's own docs
rather than from memory.

Current Angular idiom: standalone components, signals, `@if`/`@for`, `provideHttpClient(withFetch())`,
typed reactive forms.

Generate the API client from the server's OpenAPI spec at
`http://localhost:8051/vidingest/v3/api-docs`. Do not hand-write endpoint constants —
`VidIngestApiPaths.java` is the server-side source of truth and the spec mirrors it.

**Cost: zero skill body.** Phase 1's direction is still in context and does the styling work.

### Phase 3 — Motion (skip unless needed)

Only if the UI actually animates. Core first:

```
/gsap-core
```

Then, and only for scroll-linked work:

```
/gsap-scrolltrigger
```

For this project the one place that earns ScrollTrigger is the transcript timeline — scrubbing
a long segment list against video position. Everything else (panel transitions, phase-status
changes) is a CSS transition, and `gsap-core`'s `gsap.matchMedia()` section covers the
`prefers-reduced-motion` handling you need either way.

**Cost: 254, or 550 for both.** Skip the phase entirely and you keep half your budget.

### Phase 4 — Audit

```
/web-design-guidelines applications/webapp/src/**/*.html
```

Fetches the current Vercel Web Interface Guidelines, reads your files, returns findings in
terse `file:line` form. Run it **last**, after the code exists — it audits, it never generates.

Requires network at this moment. On a restricted-egress machine, fetch the rules once and pass
them in by hand:

```bash
curl -sL https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md -o docs/wig-rules.md
```

**Cost: 39 lines + ~2k fetched tokens.**

---

## Worked example — end to end

Task: **the VidIngest run-detail page.**

| Phase | Action | Result |
|---|---|---|
| 0 | `search.py "operator console, pipeline monitoring" --domain style --stack angular --design-system --persist` | Style + palette + type, persisted. No context spent |
| 0 | `search.py "status colors, 8 pipeline phases" --domain color` | Semantic status ramp instead of eight invented hexes |
| 1 | `/frontend-design` + brief + phase-0 output | Committed direction, layout, type scale |
| 2 | `ng new` + one component library + generated API client | Stepper, table, search wired to real endpoints |
| 3 | *skipped* | Nothing scroll-linked on this page. 550 lines saved |
| 4 | `/web-design-guidelines applications/webapp/src/app/runs/**` | Focus rings, ARIA on the phase stepper, contrast on muted status text |

Total skill body in context: **55 + 39 = 94 lines**, versus 858 if everything fires.

---

## Enforcing the order

Descriptions overlap, so the model will not reliably pick this order on its own. Add to
[CLAUDE.md](../CLAUDE.md):

```markdown
## Frontend skills

Order matters — these five overlap. Follow it:

1. `ui-ux-pro-max` — run `.claude/skills/ui-ux-pro-max/scripts/search.py` for palette/type/style
   candidates. Lookup only,
   never as a generator, never after step 2.
2. `frontend-design` — commit to one direction, using step 1's output as the input.
3. Component library — Angular Material, PrimeNG or `@spartan-ng/ui`. `shadcn` is disabled;
   it is React-only.
4. `gsap-core`, then `gsap-scrolltrigger` — only when the UI actually animates.
5. `web-design-guidelines` — last, on written code. Audits only.

Never run `ui-ux-pro-max` and `frontend-design` in the same turn.
```

For deterministic enforcement rather than a bias, use a hook. A CLAUDE.md block is a strong
nudge, not a guarantee.

---

## Cost control

Make the situational skills cost **zero** context until you type their slash command. Add to
`.claude/settings.local.json`:

```json
{
  "skillOverrides": {
    "shadcn": "off",
    "gsap-core": "user-invocable-only",
    "gsap-scrolltrigger": "user-invocable-only",
    "ui-ux-pro-max": "user-invocable-only"
  }
}
```

This file is already written. `"off"` hides `shadcn` from Claude and from the `/` menu — the
right state for a React-only skill in an Angular repo. To remove it from disk entirely:
`rm -rf .claude/skills/shadcn`.

`"user-invocable-only"` hides the skill from Claude's listing — its description leaves context
entirely — while keeping `/gsap-core` typable. Exactly right for the three that are either
phase-gated or over-broad.

Leave `frontend-design` and `web-design-guidelines` on: their descriptions are short and you
want them discoverable.

Note the asymmetry: `shadcn` ships `user-invocable: false` (Claude-only by design), so a
`"user-invocable-only"` override would leave it invokable by nobody. `"off"` is the correct
way to retire it.

`/skills` writes this file for you — highlight a skill, `Space` cycles the state, `Enter` saves.

---

## Notes

- `ui-ux-pro-max` was flagged **High Risk** by the installer's Gen assessment. It ships Python
  that the agent executes. The scripts were reviewed as real data-search code on install, but
  re-check `scripts/` after any upgrade — skills run with full agent permissions.
- Installed from GitHub (`nextlevelbuilder/ui-ux-pro-max-skill`) rather than a marketplace, on
  purpose: marketplace copies have been reported shipping data and scripts as pointer files.
- All six live in `.claude/skills/` and are project-scoped. Nothing was installed globally.

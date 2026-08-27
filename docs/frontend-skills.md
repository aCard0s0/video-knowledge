# Frontend design skills

- **Last reviewed**: 2026-08-27
- **Applies to**: `.claude/skills/` in this repository (project-scoped, not global)
- **Status**: installed and configured for Angular; the console they were installed for is built

Five live agent skills for building UI, plus a sixth installed and disabled. This document is the
**order** to invoke them in, because their trigger descriptions overlap and the wrong order costs
both context and quality.

---

## Where this stands

The console exists. [applications/webapp/](../applications/webapp/) is an Angular 22 **zoneless**
app — standalone components, signals, `@if`/`@for`, typed reactive forms, lazy routes, SCSS —
with eight screens under `src/app/features/`, a generated API client under `src/app/api/generated/`,
and the design tokens in
[src/styles/_tokens.scss](../applications/webapp/src/styles/_tokens.scss). It builds into the
server jar and is served from `classpath:/static`. [CLAUDE.md](../CLAUDE.md#frontend) carries the
full picture and every hard-won API fact behind it.

So phases 0-2 below have already run once, and this document is now about the **repeat** case: a
new screen, a reshaped one, an audit of what is there. The order is what still matters — the
skills' trigger descriptions overlap the same way they did on day one.

**Four of the six carry over**: `frontend-design`, `web-design-guidelines` and both GSAP skills are
framework-agnostic, and `ui-ux-pro-max` supports `--stack angular`. **`shadcn` does not** — it is
React-only and is `"off"` in `.claude/settings.local.json`.

**There is no component library, and that is a decision rather than a gap.** The eight primitives
in [src/app/ui/](../applications/webapp/src/app/ui/) — `empty`, `fault`, `lane`, `pager`,
`phase-picker`, `problem`, `rejects`, `status-badge` — are hand-rolled against the token file, which
is what lets both themes stay measured (the dark and light ramps are separate, not an inversion).
Reaching for Angular Material, PrimeNG or `@spartan-ng/ui` now would import a second, unthemed
source of truth for colour. Match the existing primitives instead.

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
re-deriving one. It already ran once, and landed in
[design-system/vidingest-console/MASTER.md](../design-system/vidingest-console/MASTER.md).

**Read that file as a candidate list, never as the system.** It is regenerable CLI output and is
never hand-edited, it is a *light-mode landing-page* template, and several of its values are
unusable in a dense dark console. [_tokens.scss](../applications/webapp/src/styles/_tokens.scss)
overrides it and is the real source of truth. Re-running `--persist` overwrites `MASTER.md` and
changes nothing about the app.

Then narrow:

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

> Add the <name> screen to the VidIngest console. Direction from ui-ux-pro-max: <style>, palette
> <hexes>, type <pairing>. Dense data tool, dark-first, operators watch it for hours. No gradients.

The skill forces four answers before CSS — purpose, tone, constraints, differentiation — then
commits to one extreme. Feeding it phase 0's output turns "pick an extreme" into "execute this
extreme", which is what you want on a real product rather than a landing page.

For a screen inside the console the direction is **already committed** — it is the token file and
the eight primitives. Invoke this phase only when the brief is genuinely new visual territory; for
anything that reuses the existing chrome, skip straight to phase 2 and match what is there.

Its description is thin, so **invoke it explicitly**. Do not rely on auto-trigger.

**Cost: 55 lines.**

### Phase 2 — Build

The app was scaffolded with `ng new webapp --style=scss --routing --ssr=false`; there is nothing
left to scaffold. Work inside it:

```bash
cd applications/webapp && npm start
```

Current Angular idiom, and what this app actually uses: **zoneless**, standalone components,
signals, `@if`/`@for`, `provideHttpClient(withFetch())`, typed reactive forms, lazy routes. New
screens go under `src/app/features/<name>/`, get a lazy route in `app.routes.ts` with a `title`, and
reuse [src/app/ui/](../applications/webapp/src/app/ui/) rather than growing a ninth primitive for a
one-off.

The API client is **generated, never hand-written** — `npm run api:gen` against the server's
OpenAPI spec at `http://localhost:8051/vidingest/v3/api-docs`. `VidIngestApiPaths.java` is the
server-side source of truth and the spec mirrors it. `--type-mappings=set=Array` is load-bearing;
see [CLAUDE.md](../CLAUDE.md#frontend) for why, and for the enums springdoc emits as bare `string`
that live by hand in `src/app/core/domain.ts`.

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

**Nothing in the console imports GSAP today, and that has held up.** The motion that exists is
four `@keyframes` and a handful of CSS transitions: the caret in the wordmark
([app.scss](../applications/webapp/src/app/app.scss)), the breathing running-phase box
([ui/lane.ts](../applications/webapp/src/app/ui/lane.ts)), and the route transition in
[styles.scss](../applications/webapp/src/styles.scss), which the router drives through the
browser's own **View Transitions API** (`withViewTransitions` in
[app.config.ts](../applications/webapp/src/app/app.config.ts)) — no library, and a browser without
the API simply navigates. Reach for a page-transition library only after that stops being enough.

ScrollTrigger has **no surface here yet**. The obvious candidate — scrubbing the transcript against
the `<video>` element on the video-detail screen — does not apply as built: segments are paged 50 at
a time behind `vk-pager`, so there is no long scrollable timeline to pin or scrub. That changes only
if the segment list becomes continuous.

Whatever you add, `gsap-core`'s `gsap.matchMedia()` section covers `prefers-reduced-motion`; the CSS
already in the repo handles it with a `@media (prefers-reduced-motion: reduce)` block, which is the
cheaper answer while the motion stays declarative.

**Cost: 254, or 550 for both.** Skip the phase entirely and you keep half your budget.

### Phase 4 — Audit

```
/web-design-guidelines applications/webapp/src/app/**/*.{html,ts}
```

**The `.ts` half is not optional.** Of the 17 components, only nine keep their template in a
`.html` file — the eight primitives in `src/app/ui/` are single-file components with inline
`template:` backticks, so an `*.html` glob audits the screens and silently skips every shared
control on them.

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

Task: **the VidIngest run-detail page** — the one that was actually built this way.

| Phase | Action | Result |
|---|---|---|
| 0 | `search.py "operator console, pipeline monitoring" --domain style --stack angular --design-system --persist` | Style + palette + type, persisted to `design-system/vidingest-console/MASTER.md`. No context spent |
| 0 | `search.py "status colors, 8 pipeline phases" --domain color` | Semantic status ramp instead of eight invented hexes — now the `--st-*` tokens |
| 1 | `/frontend-design` + brief + phase-0 output | Committed direction, layout, type scale. Both theme ramps measured against it, not inverted from it |
| 2 | Generated API client + hand-rolled primitives | Lane, table and pager wired to real endpoints — no component library |
| 3 | *skipped* | Nothing scroll-linked on this page. 550 lines saved |
| 4 | `/web-design-guidelines applications/webapp/src/app/features/runs/**` | Focus rings, ARIA on the phase lane, contrast on muted status text |

Total skill body in context: **55 + 39 = 94 lines**, versus 858 if everything fires.

Repeating it for a *new* screen is cheaper still: phases 0 and 1 have already been answered by the
token file, so a second screen normally spends 39 lines — phase 4 alone.

---

## Enforcing the order

Descriptions overlap, so the model will not reliably pick this order on its own.
[CLAUDE.md](../CLAUDE.md#frontend) already carries the nudge, at the end of its Frontend section:

> The five design skills in `.claude/skills/` run in a fixed order — see `docs/frontend-skills.md`.
> Never run `ui-ux-pro-max` and `frontend-design` in the same turn. **`shadcn` is disabled in
> `.claude/settings.local.json`: it is React-only and cannot help here.**

It **points here rather than restating the order**, on purpose: a numbered copy in CLAUDE.md would
be a second source of truth, and it would have drifted the first time one of these phases changed —
step 3 in the copy this replaced still named a component library the console never adopted. The one
rule worth spending CLAUDE.md's context on is the pairing that actively fights, and that is the
sentence that is there.

For deterministic enforcement rather than a bias, use a hook. A CLAUDE.md line is a strong
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

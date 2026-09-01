#!/usr/bin/env python3
"""Score KNOWLEDGE extraction prompts against a fixed video and a hand-listed ground truth.

Why this exists
---------------
Prompt v3 recovered 14.7 of 19 stated rules where v2 recovered 4.3, and finding that took 58 LLM
calls across eleven variants. Four changes along the way looked obviously right and measured
neutral or worse: putting PROCEDURE first in the allowed-types list, feeding the video title and
channel into the user message, forbidding the model from writing "None specified", and rewording
the prompt while keeping its structure. Two of those were only caught because the arms were
re-run interleaved rather than in blocks.

**The harness resolves about +/-3 rules at n=3.** Ten runs of one fixed configuration, across three
separate batches, scored 11-16 (sd 1.5) with batch means spanning 12.7-14.7. So:

  * a delta under ~3 rules at n=3 is noise, not a result;
  * run the arms INTERLEAVED (this script does), because a blocked comparison produced a +2.6
    effect that reversed to -0.7 when alternated;
  * raise -n above 3 before believing anything small.

Usage
-----
    # one prompt, four reps
    scripts/eval-knowledge-prompt.py -n 4 scripts/eval/prompts/v3-baseline.txt

    # A/B, interleaved automatically
    scripts/eval-knowledge-prompt.py -n 4 scripts/eval/prompts/v3-baseline.txt /tmp/candidate.txt

    # against a different runtime or model
    scripts/eval-knowledge-prompt.py --base-url http://localhost:1234/v1 \
        --model some-instruct-model scripts/eval/prompts/v3-baseline.txt

Results cache in --out-dir, so an interrupted sweep resumes instead of re-paying for calls it
already made. Delete the directory to force a fresh run.

The prompt files are the *system* message, verbatim. After editing KnowledgeExtractionPrompt,
regenerate the baseline -- the test that detects the drift is also what fixes it:

    ./mvnw -pl applications/vidingest/vidingest-server test \
        -Dtest=KnowledgeExtractionPromptTest -DupdateEvalBaseline=true

Until you do, KnowledgeExtractionPromptTest.baselineEvalPromptIsInSyncWithTheCode fails, so the
eval can never quietly score a prompt the server no longer sends.
"""

import argparse
import json
import os
import statistics
import sys
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FIXTURE = os.path.join(REPO_ROOT, "scripts", "eval", "knowledge-fixture.json")

# Mirrors KnowledgeUnitType. Only used to build the response schema, the same way
# KnowledgeUnitJson.unitsResponseSchema() does from the enum.
UNIT_TYPES = ["ENTITY", "TOPIC", "SUMMARY", "CLAIM", "PROCEDURE", "QUESTION"]


# --------------------------------------------------------------------------- request

def units_response_schema():
    """The same shape KnowledgeUnitJson.unitsResponseSchema() sends, so the eval and the server
    constrain the model identically. A looser schema here would flatter a prompt that only works
    when the server is stricter."""
    unit = {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": UNIT_TYPES},
            "title": {"type": "string"},
            "content": {"type": "string"},
            "salience": {"type": "number", "minimum": 0, "maximum": 1},
            "source_segment_indices": {"type": "array", "items": {"type": "integer", "minimum": 0}},
            "start_seconds": {"type": "number"},
            "end_seconds": {"type": "number"},
            "entity_type": {
                "type": "string",
                "enum": ["PERSON", "ORGANIZATION", "LOCATION", "PRODUCT", "TICKER", "WORK", "OTHER"],
            },
        },
        "required": ["type", "content"],
    }
    return {
        "type": "object",
        "properties": {"units": {"type": "array", "items": unit}},
        "required": ["units"],
    }


def user_message(segments, starting_index=0):
    """Byte-for-byte what KnowledgeExtractionPrompt.userMessage renders. Verified against the
    server's own `inputChars=` log line (11435 for this fixture) -- if you change this, check that
    number again, because an eval running on a different user message is measuring nothing."""
    out = [
        f"Extract knowledge units from the following {len(segments)} segments. "
        "The 'index' field is the value to use in source_segment_indices.\n\n"
    ]
    for i, seg in enumerate(segments):
        out.append(f"---\nindex: {starting_index + i}\n")
        if seg.get("startSeconds") is not None and seg.get("endSeconds") is not None:
            out.append(f"time: [{seg['startSeconds']:.2f}, {seg['endSeconds']:.2f}]\n")
        if (seg.get("transcriptText") or "").strip():
            out.append(f"transcript: {seg['transcriptText'].strip()}\n")
        if (seg.get("ocrText") or "").strip():
            out.append(f"on_screen_text: {seg['ocrText'].strip()}\n")
    out.append("---\n")
    return "".join(out)


def call_llm(base_url, model, system, user, max_tokens, temperature, timeout):
    body = {
        "model": model,
        "stream": False,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "knowledge_units", "strict": True, "schema": units_response_schema()},
        },
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        envelope = json.load(resp)

    choice = envelope["choices"][0]
    content = choice["message"].get("content") or ""
    finish = choice.get("finish_reason")
    if finish == "length":
        # A reasoning model on a runtime that ignores response_format lands here: it spends the
        # budget on chain-of-thought and returns no JSON at all. Say so, rather than reporting the
        # prompt as scoring zero.
        raise RuntimeError(
            f"finish_reason=length -- the model hit max_tokens ({max_tokens}) without closing its "
            "JSON. If this is a reasoning model, the runtime is not enforcing response_format and "
            "the KNOWLEDGE phase will fail against it too, not merely score badly."
        )
    parsed = json.loads(content)
    if isinstance(parsed, dict):
        for key in ("units", "knowledge_units", "items", "data", "results"):
            if isinstance(parsed.get(key), list):
                return parsed[key]
        raise RuntimeError(f"no units array; root keys={list(parsed)}")
    return parsed


# --------------------------------------------------------------------------- scoring

def hit(blob, groups):
    return any(all(term.lower() in blob for term in group) for group in groups)


def score_run(units, facts, noise):
    blob = " ".join(f"{u.get('title', '')} {u.get('content', '')}" for u in units).lower()
    found = [f["label"] for f in facts if hit(blob, f["any_of"])]
    noisy = sum(
        1
        for u in units
        for n in noise
        if hit(f"{u.get('title', '')} {u.get('content', '')}".lower(), n["any_of"])
    )
    return {
        "units": len(units),
        "procedures": sum(1 for u in units if u.get("type") == "PROCEDURE"),
        "facts": found,
        "noise": noisy,
    }


def spread(values):
    lo, hi = min(values), max(values)
    mean = statistics.mean(values)
    return f"{mean:.1f}" + (f" ({lo}-{hi})" if lo != hi else "")


def report(results, facts, arms):
    print(f"\n{'arm':22} {'reps':>4} {'units':>12} {'proc':>11} {'facts/' + str(len(facts)):>12} {'noise':>10}")
    print("-" * 76)
    for arm in arms:
        runs = [r for r in results[arm] if r]
        if not runs:
            print(f"{arm[:22]:22} {0:4}   (every rep failed)")
            continue
        print(
            f"{arm[:22]:22} {len(runs):4} "
            f"{spread([r['units'] for r in runs]):>12} "
            f"{spread([r['procedures'] for r in runs]):>11} "
            f"{spread([len(r['facts']) for r in runs]):>12} "
            f"{spread([r['noise'] for r in runs]):>10}"
        )

    print("\nfact recall, reps hit / reps run")
    width = max(len(f["label"]) for f in facts) + 2
    print(f"{'fact':{width}}" + "".join(f"{a[:12]:>14}" for a in arms))
    for f in facts:
        cells = ""
        for arm in arms:
            runs = [r for r in results[arm] if r]
            n = sum(1 for r in runs if f["label"] in r["facts"])
            cells += f"{f'{n}/{len(runs)}':>14}"
        print(f"{f['label']:{width}}" + cells)

    if len(arms) == 2:
        a, b = arms
        va = [len(r["facts"]) for r in results[a] if r]
        vb = [len(r["facts"]) for r in results[b] if r]
        if va and vb:
            delta = statistics.mean(va) - statistics.mean(vb)
            print(f"\ndelta ({a} - {b}) = {delta:+.1f} rules")
            if abs(delta) < 3:
                print("  INSIDE the ~+/-3-rule noise floor. This is not a result. Raise -n, or")
                print("  treat the two prompts as equivalent and choose on other grounds.")
            else:
                print("  Outside the noise floor at this n, but confirm with a larger -n before")
                print("  writing the number into a comment as a finding.")


# --------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("prompts", nargs="+", help="system-prompt file(s); 2+ are compared interleaved")
    ap.add_argument("-n", "--reps", type=int, default=3, help="reps per prompt (default 3; 3 is the noise floor, prefer 6)")
    ap.add_argument("--fixture", default=DEFAULT_FIXTURE)
    ap.add_argument("--base-url", default=os.environ.get("VK_EVAL_BASE_URL", "http://127.0.0.1:8000/v1"))
    ap.add_argument("--model", default=os.environ.get("VK_EVAL_MODEL", "Qwen2.5-14B-Instruct-4bit"))
    ap.add_argument("--max-tokens", type=int, default=8192, help="match vidingest.knowledge.max-output-tokens")
    ap.add_argument("--temperature", type=float, default=0.2, help="match vidingest.knowledge.temperature")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--out-dir", default=None, help="cache dir for raw results (default: alongside the fixture)")
    args = ap.parse_args()

    fixture = json.load(open(args.fixture))
    segments, facts, noise = fixture["segments"], fixture["facts"], fixture["noise"]
    user = user_message(segments)

    out_dir = args.out_dir or os.path.join(os.path.dirname(os.path.abspath(args.fixture)), "runs")
    os.makedirs(out_dir, exist_ok=True)

    arms = [os.path.splitext(os.path.basename(p))[0] for p in args.prompts]
    if len(set(arms)) != len(arms):
        sys.exit("prompt files must have distinct basenames -- they name the arms in the report")
    systems = {arm: open(p).read() for arm, p in zip(arms, args.prompts)}

    print(f"model={args.model}  base-url={args.base_url}")
    print(f"fixture={os.path.relpath(args.fixture, REPO_ROOT)}  segments={len(segments)}  userChars={len(user)}")
    print(f"arms={arms}  reps={args.reps}  interleaved")
    if args.reps < 3:
        print("WARNING: fewer than 3 reps cannot separate a real effect from this harness's noise.")

    results = {arm: [] for arm in arms}
    # Interleaved on purpose: a blocked A-then-B sweep measured +2.6 rules for a change that
    # measured -0.7 when alternated. Whatever drifts over a sweep hits both arms equally this way.
    for rep in range(args.reps):
        for arm in arms:
            cached = os.path.join(out_dir, f"{arm}_{args.model}_{rep}.json")
            if os.path.exists(cached):
                units = json.load(open(cached))
                print(f"  {arm} rep{rep}: cached")
            else:
                try:
                    units = call_llm(args.base_url, args.model, systems[arm], user,
                                     args.max_tokens, args.temperature, args.timeout)
                except (urllib.error.URLError, OSError) as e:
                    print(f"  {arm} rep{rep}: TRANSPORT FAILED -- {e}")
                    results[arm].append(None)
                    continue
                except Exception as e:
                    print(f"  {arm} rep{rep}: FAILED -- {e}")
                    results[arm].append(None)
                    continue
                json.dump(units, open(cached, "w"), indent=1)
                print(f"  {arm} rep{rep}: {len(units)} units, "
                      f"{sum(1 for u in units if u.get('type') == 'PROCEDURE')} procedures")
            results[arm].append(score_run(units, facts, noise))

    report(results, facts, arms)
    print(f"\nraw results in {os.path.relpath(out_dir, REPO_ROOT)} (delete to re-run)")
    return 0 if any(r for arm in arms for r in results[arm]) else 1


if __name__ == "__main__":
    sys.exit(main())

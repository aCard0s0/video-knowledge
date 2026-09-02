#!/usr/bin/env bash
# scripts/vk-selftest.sh — exercises every command in ./vk. Run it as `./vk test cli`.
#
# ./vk is 590 lines of bash with no other test, and the project-cli skill it follows makes
# verification-by-execution mandatory rather than optional. This is that. It found two real
# defects on its first run: `down` advertised `--yes` in its registry row and its own help
# text while the parser rejected it, and `clean --all` deleted node_modules with no prompt
# where the vocabulary requires confirm().
#
# NOT hermetic, which is why it is a named suite and never part of bare `./vk test`:
#   - it stops, starts and restarts the postgres container (narrow and reversible)
#   - it runs the real `clean`, so the maven target/ trees go
#   - it bounds `dev` and `logs -f` with alarm(2) to prove they block, then kills them
# Destructive paths are never aimed at the real stack: `down --volumes` is exercised against
# VK_PROJECT=vk-selftest, an isolated compose project that shares no container or volume,
# and the harness asserts all five real containers are still up afterwards.
#
# Deliberately NOT using set -e: a failing check is data, not a reason to stop.
cd "$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" || exit 1
export NO_COLOR=1
PASS=0; FAIL=0; OUT=$(mktemp)
SCRATCH=vk-selftest            # an isolated compose project: shares no container or volume

# macOS has no `timeout`. This bounds a command without a sleep loop and returns 142 when it
# was still running at the bound — i.e. when it blocks, which for `dev` and `logs -f` is the
# contract being asserted.
#
# `set -m` is load-bearing: it makes the background job a process-group leader, so the kill
# reaches the whole tree. The first version of this signalled only the direct child, and
# `ng serve` survived as an orphan holding :4200 — which then made the *next* `dev` check
# fail for an unrelated reason. A harness that leaks a server is worse than no harness.
# Snapshot what is running BEFORE anything here touches docker. The three "did this test
# disturb the stack?" assertions compared against a literal 5, which was true for the
# default footprint and wrong the moment `--serve` added a sixth container — so they failed
# for a reason that had nothing to do with what they test.
STACK_BASELINE=$(docker ps --filter name=video-knowledge --format '{{.Names}}' 2>/dev/null | wc -l | tr -d ' ')
intact() { # intact <label>
  local now; now=$(docker ps --filter name=video-knowledge --format '{{.Names}}' 2>/dev/null | wc -l | tr -d ' ')
  if [ "$now" = "$STACK_BASELINE" ]; then PASS=$((PASS+1)); printf '  ok   %-58s containers=%s\n' "$1" "$now"
  else FAIL=$((FAIL+1)); printf '  FAIL %-58s baseline=%s now=%s\n' "$1" "$STACK_BASELINE" "$now"; fi
}

bounded() {
  local s="$1"; shift
  set -m; "$@" >"$OUT" 2>&1 & local pid=$!; set +m
  if perl -e 'my ($p,$s)=@ARGV; $SIG{ALRM}=sub{exit 1}; alarm $s;
              while (1) { kill(0,$p) or exit 0; select(undef,undef,undef,0.2) }' "$pid" "$s"; then
    wait "$pid"; return $?
  fi
  kill -TERM -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  return 142
}
# Nothing may be listening on 4200 before the dev checks, or `dev` correctly exits 4 and the
# blocking assertion below reads as a failure.
free_4200() { have_lsof && lsof -ti tcp:4200 -sTCP:LISTEN 2>/dev/null | xargs 2>/dev/null kill; return 0; }
have_lsof() { command -v lsof >/dev/null 2>&1; }

ck() { # ck <want-exit> <label> <cmd...>
  local want="$1" label="$2"; shift 2
  "$@" >"$OUT" 2>&1; local got=$?
  if [ "$got" = "$want" ]; then PASS=$((PASS+1)); printf '  ok   %-58s exit=%s\n' "$label" "$got"
  else FAIL=$((FAIL+1)); printf '  FAIL %-58s want=%s got=%s\n' "$label" "$want" "$got"
       sed 's/^/         | /' "$OUT" | head -4; fi
}
ckout() { # ckout <want-exit> <must-contain> <label> <cmd...>
  local want="$1" needle="$2" label="$3"; shift 3
  "$@" >"$OUT" 2>&1; local got=$?
  if [ "$got" = "$want" ] && grep -qF -- "$needle" "$OUT"; then
    PASS=$((PASS+1)); printf '  ok   %-58s exit=%s\n' "$label" "$got"
  else FAIL=$((FAIL+1)); printf '  FAIL %-58s want=%s/%s got=%s\n' "$label" "$want" "$needle" "$got"
       sed 's/^/         | /' "$OUT" | head -4; fi
}
cklines() { # cklines <n> <label> <cmd...>
  local want="$1" label="$2"; shift 2
  local n; n=$("$@" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$n" = "$want" ]; then PASS=$((PASS+1)); printf '  ok   %-58s lines=%s\n' "$label" "$n"
  else FAIL=$((FAIL+1)); printf '  FAIL %-58s want=%s got=%s\n' "$label" "$want" "$n"; fi
}

echo "═══ 1. discovery contract ═══"
ck      0 "bare invocation prints surface, exit 0"        ./vk
ckout   0 "Commands:" "bare output has the command list"  ./vk
ck      0 "help"                                          ./vk help
ck      0 "help start"                                    ./vk help start
ck      2 "help nosuchcmd -> 2"                           ./vk help nosuchcmd
ck      0 "-h"                                            ./vk -h
ck      0 "--help"                                        ./vk --help
ck      0 "-V"                                            ./vk -V
ck      2 "unknown command -> 2"                          ./vk nosuchcmd
ckout   2 "did you mean" "unknown command suggests"       ./vk stat
ckout   2 "is a target, not a command" "target-as-verb hint" ./vk vidingest
ck      2 "unknown global option -> 2"                    ./vk --bogus status

echo "═══ 2. <cmd> --help is universal and side-effect free ═══"
for c in start stop restart status logs list setup dev test fmt build down clean shell console doctor version help; do
  ck 0 "$c --help" ./vk "$c" --help
done
intact "all --help left the stack alone"

echo "═══ 3. list ═══"
cklines 7 "list -> 7 services"                            ./vk list
cklines 7 "list services -> 7"                            ./vk list services
cklines 4 "list groups -> 4"                              ./vk list groups
ck      2 "list bogus -> 2"                               ./vk list bogus
ck      2 "list takes one kind -> 2"                      ./vk list services groups

echo "═══ 4. version / doctor (inverse exit contracts) ═══"
ckout   0 "project-cli vocabulary" "version names the contract rev" ./vk version
ck      2 "version extra -> 2"                            ./vk version extra
ckout   0 "healthy" "doctor healthy -> 0"                 ./vk doctor
ck      2 "doctor extra -> 2"                             ./vk doctor extra
ckout   4 "not usable" "doctor -> 4 when a dep is unusable" env VK_HOST_LLM_URL=http://127.0.0.1:9 ./vk doctor

echo "═══ 5. status: exit 0 whenever observation succeeded ═══"
ck      0 "status (stack up)"                             ./vk status
ck      0 "status vidingest"                              ./vk status vidingest
ck      0 "status backend (group)"                         ./vk status backend
ck      0 "status with NOTHING deployed -> still 0"       env VK_PROJECT="$SCRATCH" ./vk status
ckout   0 "127.0.0.1:8051" "status reports the measured port" ./vk status
ck      2 "status --bogus -> 2"                           ./vk status --bogus
ck      2 "status nosuchtarget -> 2"                      ./vk status nosuchtarget

echo "═══ 6. logs: bounded by default, -f is the only blocking path ═══"
ck      0 "logs vidingest -n 5"                           ./vk logs vidingest -n 5
ck      0 "logs --tail 3 postgres"                        ./vk logs --tail 3 postgres
ck      2 "logs -n with no value -> 2"                    ./vk logs -n
ck      2 "logs --bogus -> 2"                             ./vk logs --bogus
ck      2 "logs nosuchtarget -> 2"                        ./vk logs nosuchtarget
ck    142 "logs -f BLOCKS (killed at 4s)"                 bounded 4 ./vk logs -f vidingest

echo "═══ 7. start / stop / restart — real, narrow, reversible (postgres) ═══"
ck      0 "stop postgres"                                 ./vk stop postgres
ck      0 "status after stop still exits 0"               ./vk status postgres
ck      0 "start postgres (health-gated)"                 ./vk start postgres
ck      0 "start postgres again = idempotent"             ./vk start postgres
ck      0 "restart postgres"                              ./vk restart postgres
ck      2 "start --bogus -> 2"                            ./vk start --bogus
ck      2 "stop --build -> 2 (no accept-and-ignore)"      ./vk stop --build
ck      2 "start nosuchtarget -> 2"                       ./vk start nosuchtarget
ckout   0 "vidingest webapp" "dry-run start backend expands the group" ./vk --dry-run start backend
ckout   0 "up -d --wait" "dry-run start shows the resolved compose line" ./vk --dry-run start

echo "═══ 8. build (dry-run: a real build is minutes) ═══"
ckout   0 "dc build" "dry-run build"                      ./vk --dry-run build
ckout   0 "--no-cache" "dry-run build --no-cache"         ./vk --dry-run build --no-cache
ck      2 "build --bogus -> 2"                            ./vk build --bogus
ck      2 "build nosuchtarget -> 2"                       ./vk build nosuchtarget

echo "═══ 9. down — destructive, so: scratch project + guard paths only ═══"
ck      0 "down on an absent project = idempotent 0"      env VK_PROJECT="$SCRATCH" ./vk down
ck      2 "down --volumes refuses non-interactive stdin"  sh -c './vk down --volumes < /dev/null'
ck      0 "down --volumes --yes on the scratch project"   env VK_PROJECT="$SCRATCH" ./vk down --volumes --yes
ck      0 "down accepts --yes post-verb, as its row promises" env VK_PROJECT="$SCRATCH" ./vk down -y --volumes
ck      2 "down takes no targets -> 2"                    ./vk down webapp
ck      2 "down --bogus -> 2"                             ./vk down --bogus
ckout   0 "--volumes" "dry-run down --volumes --yes"      ./vk --dry-run down --volumes --yes
intact "the real stack survived every down test"

echo "═══ 10. clean — real (regenerable only), and provably no container reach ═══"
ck      2 "clean nosuchtarget -> 2"                       ./vk clean nosuchtarget
ck      2 "clean --bogus -> 2"                            ./vk clean --bogus
ckout   0 "node_modules" "dry-run clean --all names node_modules" ./vk --dry-run clean --all --yes
ck      2 "clean --all refuses non-interactive without --yes" bash -c './vk clean --all < /dev/null'
ck      0 "clean --all --yes is driveable"                 ./vk --dry-run clean --all --yes
ck      0 "clean (real)"                                  ./vk clean
if [ -d applications/vidingest/vidingest-server/target ]; then
  FAIL=$((FAIL+1)); printf '  FAIL %-58s target/ survived clean\n' "clean removed the maven target trees"
else PASS=$((PASS+1)); printf '  ok   %-58s target/ gone\n' "clean removed the maven target trees"; fi
if [ -d applications/webapp/node_modules ]; then
  PASS=$((PASS+1)); printf '  ok   %-58s node_modules intact\n' "bare clean left node_modules alone"
else FAIL=$((FAIL+1)); printf '  FAIL %-58s bare clean ate node_modules\n' "bare clean left node_modules alone"; fi
intact "clean cannot reach a container"

echo "═══ 11. setup ═══"
ckout   0 "already installed" "setup is idempotent"       ./vk setup
ck      0 "setup twice"                                   ./vk setup
ckout   0 "npm ci" "dry-run setup --force re-runs npm ci" ./vk --dry-run setup --force
ck      2 "setup extra -> 2"                              ./vk setup extra
ck      2 "setup --bogus -> 2"                            ./vk setup --bogus

echo "═══ 12. fmt ═══"
ck      1 "fmt --check exits 1 on drift"                  ./vk fmt --check
ck      0 "fmt --check listed no generated file"          sh -c '! ./vk fmt --check 2>&1 | grep -q "api/generated"'
ckout   0 "prettier --write" "dry-run fmt would write"    ./vk --dry-run fmt
ck      2 "fmt nosuchtarget -> 2"                         ./vk fmt nosuchtarget
ck      2 "fmt --bogus -> 2"                              ./vk fmt --bogus

echo "═══ 13. shell / console (dry-run: both are interactive) ═══"
ckout   0 "exec vidingest sh" "dry-run shell defaults to vidingest" ./vk --dry-run shell
ckout   0 "exec postgres sh" "dry-run shell --in postgres" ./vk --dry-run shell --in postgres
ck      2 "shell --in nosuchsvc -> 2"                     ./vk shell --in nosuchsvc
ck      2 "shell with a bare name -> 2"                   ./vk shell vidingest
ck      2 "shell --in with no value -> 2"                 ./vk shell --in
ckout   0 "up -d vidingest-cli" "dry-run console"         ./vk --dry-run console
ck      2 "console extra -> 2"                            ./vk console extra

echo "═══ 14. test ═══"
ck      2 "test bogussuite -> 2"                          ./vk test bogussuite
ck      2 "test --bogus -> 2"                             ./vk test --bogus
ckout   0 "Omitted: integration" "bare test names the skipped suite" ./vk --dry-run test
ckout   0 "-Dtest=FusePhaseTest" "test passes -- through"  ./vk --dry-run test server -- -Dtest=FusePhaseTest

echo "═══ 15. dev — blocks by contract ═══"
ck      2 "dev with an arg before -- -> 2"                ./vk dev extra
ckout   0 "npm start" "dry-run dev"                       ./vk --dry-run dev
free_4200
ck    142 "dev BLOCKS serving on :4200 (killed at 25s)"   bounded 25 ./vk dev
free_4200
# The precondition the vocabulary spells exit 4. Faked with a listener rather than a real
# dev server so the check costs nothing.
if have_lsof; then
  perl -e 'use IO::Socket::INET; my $s=IO::Socket::INET->new(LocalAddr=>"127.0.0.1",LocalPort=>4200,Listen=>1,ReuseAddr=>1) or exit 9; sleep 30' &
  HOLDER=$!
  perl -e 'select(undef,undef,undef,0.7)'
  ck    4 "dev -> 4 when :4200 is already taken"          ./vk dev
  kill "$HOLDER" 2>/dev/null; wait "$HOLDER" 2>/dev/null
fi

echo "═══ 16. tailnet layering (no auth key needed — nothing is started) ═══"
FAKE="TS_IMAGE_TAG=v1.102.3 TS_AUTHKEY=tskey-selftest-fake"
# The layering has to be identical whether the flag came before or after the verb, because
# `logs`/`down`/`status` need the same -f set as the `up` that made the stack.
layer() { bash -c "source ./vk; extract_layering_flags $1; resolve_layering; printf '%s|%s' \"\${TAILSCALE_ON:-no}\" \"\${LOCAL_PORT_ON:-no}\""; }
for pair in "::no|1" "--serve https::1|no" "--serve https --local::1|1" "--no-local::no|no" "--serve funnel::1|no"; do
  args="${pair%%::*}"; want="${pair##*::}"; got=$(layer "$args")
  if [ "$got" = "$want" ]; then PASS=$((PASS+1)); printf '  ok   %-58s %s\n' "layering [${args:-default}]" "$got"
  else FAIL=$((FAIL+1)); printf '  FAIL %-58s want=%s got=%s\n' "layering [${args:-default}]" "$want" "$got"; fi
done
ck      2 "--serve with no mode -> 2"                     ./vk --serve
ck      2 "--serve bogus -> 2"                            ./vk --serve bogus status
ck      0 "--serve accepted after the verb too"           bash -c "$FAKE ./vk --dry-run status --serve https"
# The overlay uses ${TS_AUTHKEY:?}, which would break every read-only compose call; dc()
# passes placeholders so `status` and `logs` never need the real key to list containers.
ck      0 "read-only calls survive the required interpolations" bash -c 'TS_IMAGE_TAG= TS_AUTHKEY= ./vk --serve https status'
ck      4 "start --serve without TS_AUTHKEY -> 4"         bash -c 'VK_ENV_FILE=/dev/null TS_IMAGE_TAG=v1.102.3 TS_AUTHKEY= ./vk start --serve https'
# The regression that shipped: vk validated TS_* from its own shell environment, but the
# documented home for them is .env, which only reaches COMPOSE via --env-file. A correctly
# configured .env therefore read as missing and `start --serve` died 4 on a working setup.
ENVTMP=$(mktemp)
printf 'TS_IMAGE_TAG=v1.102.3\nTS_AUTHKEY=fake-from-envfile   # inline comment\n' > "$ENVTMP"
ckout   0 "tailscale/tailscale:v1.102.3" \
        "TS_* are read from the env file, not just the shell" \
        bash -c "VK_ENV_FILE='$ENVTMP' TS_IMAGE_TAG= TS_AUTHKEY= ./vk --serve https doctor"
# The bug that produced a plain 502 from a node that was Running and healthy, with a
# correct-looking `tailscale serve status`: compose.yml declares a NAMED network that every
# service joins explicitly, and the sidecar omitted it, so it landed on the implicit
# `default` and could not resolve `webapp` at all. Static — needs nothing started.
ck      0 "the sidecar shares a network with its backend" bash -c '
  source ./vk; extract_layering_flags --serve https; resolve_layering
  TS_IMAGE_TAG=v1.102.3 TS_AUTHKEY=x docker compose -p video-knowledge "${COMPOSE_FILES[@]}" config --format json 2>/dev/null |
    python3 -c "
import json,sys
s=json.load(sys.stdin)[\"services\"]
ts=set((s[\"tailscale\"].get(\"networks\") or {}).keys())
web=set((s[\"webapp\"].get(\"networks\") or {}).keys())
assert ts and web, f\"a service declares no network: tailscale={ts} webapp={web}\"
assert ts & web, f\"sidecar on {ts}, backend on {web} - Serve cannot resolve the backend\"
"'
ckout   0 "ts key    present" \
        "an inline # comment is stripped from an env-file value" \
        bash -c "VK_ENV_FILE='$ENVTMP' TS_IMAGE_TAG= TS_AUTHKEY= ./vk --serve https doctor"
ck      0 "the key value is never printed"                 bash -c "! (VK_ENV_FILE='$ENVTMP' TS_IMAGE_TAG= TS_AUTHKEY= ./vk --serve https doctor 2>&1 | grep -q fake-from-envfile)"
rm -f "$ENVTMP"
ck      4 "TS_HOSTNAME equal to a service name -> 4"      bash -c "$FAKE TS_HOSTNAME=webapp ./vk start --serve https"
ck      2 "start --serve funnel refuses non-interactively" bash -c "$FAKE ./vk start --serve funnel < /dev/null"
ckout   0 "FUNNEL" "the funnel warning names the exposure" bash -c "$FAKE ./vk start --serve funnel < /dev/null 2>&1; true"
# The exposure assertion that matters: with --serve the console must publish NOTHING.
ck      0 "with --serve the console publishes no host port" bash -c '
  source ./vk; extract_layering_flags --serve https; resolve_layering
  TS_IMAGE_TAG=v1.102.3 TS_AUTHKEY=x docker compose -p video-knowledge "${COMPOSE_FILES[@]}" config --format json 2>/dev/null |
    python3 -c "
import json,sys
d=json.load(sys.stdin)
assert (d[\"services\"][\"webapp\"].get(\"ports\") or []) == [], \"webapp still publishes a port under --serve\"
assert \"tailscale\" in d[\"services\"], \"sidecar missing\"
assert json.dumps(d).count(\"TS_AUTHKEY\") == 1, \"TS_AUTHKEY reaches more than the sidecar\"
"'
ck      0 "without --serve the console publishes 8052 as before" bash -c '
  source ./vk; extract_layering_flags; resolve_layering
  docker compose -p video-knowledge "${COMPOSE_FILES[@]}" config --format json 2>/dev/null |
    python3 -c "
import json,sys
d=json.load(sys.stdin)
assert (d[\"services\"][\"webapp\"].get(\"ports\") or []), \"the default no longer publishes the console\"
assert \"tailscale\" not in d[\"services\"], \"sidecar leaked into the default footprint\"
"'
# `docker compose port` prints `invalid IP:0` and exits 0 when nothing is published, so a
# `[ -n "$out" ]` test reports a correctly-closed stack as exposed. It shipped as
# `console http://invalid IP:0/vidingest` on the very first real tailnet start.
ck      0 "no 'invalid IP' leaks into status output"      bash -c '! ./vk status 2>&1 | grep -q "invalid IP"'
ck      0 "published() answers empty for an unpublished port" bash -c '
  source ./vk; extract_layering_flags --serve https; resolve_layering
  out=$(published webapp 8080); [ -z "$out" ] || { echo "claimed: $out"; exit 1; }'
ck      0 "serve config targets webapp, not 127.0.0.1"    grep -q "http://webapp:8080" compose/tailscale/serve-https.json
ck      0 "both serve configs are valid JSON"             python3 -c "
import json
json.load(open('compose/tailscale/serve-https.json'))
json.load(open('compose/tailscale/serve-funnel.json'))"
# Deliberately narrow: matching every `TS_AUTHKEY=` assignment flags this harness's own
# fakes and vk's read-only placeholder, which is noise. What must never be committed is a
# real key, and those start with the `tskey-` prefix plus a key-class word.
#
# The scan covers this file too, which is why the sentence above spells no literal prefix:
# the first version of it did, and the check matched its own explanation.
ck      0 "no real tailscale key in any tracked file"     bash -c '! git grep -qE "tskey-(auth|client)-" -- . 2>/dev/null'
ck      0 "tailscale appears as a target only with --serve" bash -c '
  ./vk list | grep -q tailscale && exit 1
  source ./vk; extract_layering_flags --serve https; resolve_layering
  case " $SERVICES " in *" tailscale "*) exit 0 ;; *) exit 1 ;; esac'

echo "═══ 17. the two registry lints + the resolver ═══"
ck 0 "drift lint: every cmd_ has a row and vice versa" bash -c \
  "diff <(grep '^cmd_' ./vk | sed 's/^cmd_//;s/() .*//' | grep -v '_help\$' | sed 's/_/ /g' | sort) <(sed -n '/^COMMANDS=/,/^REGISTRY/p' ./vk | awk -F'|' 'NF>1{print \$1}' | sort)"
ck 0 "row lint: every registry row has 4 fields" bash -c \
  "[ -z \"\$(awk -F'|' '/^COMMANDS=/,/^REGISTRY/ { if (\$0 !~ /^#/ && \$0 !~ /^(COMMANDS=|REGISTRY)/ && NF>1 && NF!=4) print }' ./vk)\" ]"
ck 0 "bash -n" bash -n ./vk
ck 0 "runs from a nested directory" sh -c 'cd applications/webapp/src && ../../../vk list >/dev/null'
LINK=$(mktemp -d)/vk-link
ln -sf "$PWD/vk" "$LINK"
ck 0 "runs through a symlink from outside the repo" sh -c "cd / && $LINK list >/dev/null"
ck 0 "sourceable without executing (entrypoint guard)" sh -c 'out=$(source ./vk 2>&1); [ -z "$out" ]'

rm -f "$OUT"
echo
echo "══════════════════════════════════════════════════"
printf 'PASS=%s  FAIL=%s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1

#!/bin/bash
# ============================================================================
# Tradey — VidIngest Docker Compose manager
# ============================================================================
# Orchestrates the VidIngest backend:
#   - vidingest  (Spring Boot REST, Java 25)                       → :8051
#   - vidingest-mcp (MCP SSE server, opt-in)                       → :8055
#   - vidingest-cli (Spring Shell console, opt-in)
# plus the infra vidingest needs: timescaledb, ollama, whisper, and two
# optional sidecars (paddleocr-server, diarize-asr).
#
# Compose layout: compose.yml (base) + compose/{infra/infra,services,cli,mcp}.yml
#
# Usage: ./scripts/tradey.sh <command> [service|group] [--build] [--tag TAG]
# Examples:
#   ./scripts/tradey.sh start                 # infra + vidingest
#   ./scripts/tradey.sh start --build         # build images first, then start
#   ./scripts/tradey.sh start vidingest       # backend only (auto-starts infra)
#   ./scripts/tradey.sh start mcp             # MCP server only
#   ./scripts/tradey.sh cli                   # open the VidIngest console
#   ./scripts/tradey.sh logs -f vidingest     # follow backend logs
#   ./scripts/tradey.sh ollama pull qwen2.5:14b-instruct
#   ./scripts/tradey.sh status
#   ./scripts/tradey.sh down --volumes
#
# Image tag: built images are video-knowledge/*:TAG (default latest). Set
# VK_IMAGE_TAG or pass --tag/-t. Infra images (timescaledb, ollama, whisper) are unchanged.
# ============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${BASE_DIR}"

# Canonical host-port map (overridable via the environment).
set -a
# shellcheck disable=SC1091
[ -f "${BASE_DIR}/compose/ports.env" ] && . "${BASE_DIR}/compose/ports.env"
set +a

export VK_IMAGE_TAG="${VK_IMAGE_TAG:-latest}"
export VK_BIND_ADDR="${VK_BIND_ADDR:-127.0.0.1}"

COMPOSE=(docker compose -p video-knowledge
  -f "${BASE_DIR}/compose.yml"
  -f "${BASE_DIR}/compose/infra/infra.yml"
  -f "${BASE_DIR}/compose/services.yml"
  -f "${BASE_DIR}/compose/cli.yml"
  -f "${BASE_DIR}/compose/mcp.yml")

# ── service catalogue + groups ──────────────────────────────────────────────
ALL_SERVICES="timescaledb ollama whisper paddleocr-server diarize-asr vidingest vidingest-cli vidingest-mcp"
GROUP_INFRA="timescaledb ollama whisper"
GROUP_SIDECARS="paddleocr-server diarize-asr"
GROUP_BACKEND="vidingest"
# Default footprint for 'start'/'build all': infra + backend (no opt-in sidecars/cli/mcp)
GROUP_ALL="${GROUP_INFRA} ${GROUP_BACKEND}"

info()  { echo -e "${BLUE}→${NC} $*"; }
ok()    { echo -e "${GREEN}✓${NC} $*"; }
warn()  { echo -e "${YELLOW}!${NC} $*" >&2; }
die()   { echo -e "${RED}error:${NC} $*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null || die "docker not found on PATH"
  docker info >/dev/null 2>&1 || die "docker daemon not reachable"
}

# Resolve a service|group token (with aliases) to a space-separated service list.
resolve() {
  case "$1" in
    ""|all)            echo "${GROUP_ALL}" ;;
    infra)             echo "${GROUP_INFRA}" ;;
    sidecars)          echo "${GROUP_SIDECARS}" ;;
    backend|be)        echo "${GROUP_BACKEND}" ;;
    db)                echo "timescaledb" ;;
    vi|vidingest)      echo "vidingest" ;;
    cli)               echo "vidingest-cli" ;;
    mcp)               echo "vidingest-mcp" ;;
    *)
      # exact service name?
      for s in ${ALL_SERVICES}; do [ "$s" = "$1" ] && { echo "$1"; return; }; done
      die "unknown service/group: $1  (try: ${ALL_SERVICES} | infra sidecars backend all)"
      ;;
  esac
}

# Split trailing flags (--build/--tag) from a leading service/group token.
parse_target_and_flags() {
  TARGET=""; BUILD=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --build|-b) BUILD=1 ;;
      --tag|-t)   [ $# -ge 2 ] || die "--tag needs a value"; export VK_IMAGE_TAG="$2"; shift ;;
      --tag=*)    export VK_IMAGE_TAG="${1#--tag=}" ;;
      -*)         die "unknown flag: $1" ;;
      *)          [ -z "${TARGET}" ] || die "only one service/group at a time (got '$TARGET' and '$1')"; TARGET="$1" ;;
    esac
    shift
  done
}

cmd_start() {
  parse_target_and_flags "$@"
  require_docker
  local services; services="$(resolve "${TARGET}")"
  if [ -n "${BUILD}" ]; then
    info "building: ${services}"
    # shellcheck disable=SC2086
    "${COMPOSE[@]}" build ${services}
  fi
  info "starting: ${services}  (tag=${VK_IMAGE_TAG})"
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" up -d ${services}
  ok "up — vidingest: http://localhost:${VIDINGEST_PORT:-8051}/vidingest"
}

cmd_build() {
  parse_target_and_flags "$@"
  require_docker
  local services; services="$(resolve "${TARGET}")"
  info "building: ${services}  (tag=${VK_IMAGE_TAG})"
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" build ${services}
  ok "built (tag=${VK_IMAGE_TAG})"
}

cmd_stop() {
  require_docker
  # No target (or 'all') means every running service, including the opt-in cli/mcp
  # sidecars that GROUP_ALL deliberately leaves out of 'start'.
  local target="${1:-all}" services=""
  [ "${target}" = "all" ] || services="$(resolve "${target}")"
  info "stopping: ${services:-everything}"
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" stop ${services}
  ok "stopped"
}

# Restart the SAME target it was asked to (stop then start), honouring --build/--tag.
cmd_restart() {
  parse_target_and_flags "$@"
  cmd_stop "${TARGET:-all}"
  cmd_start "$@"
}

cmd_status() {
  require_docker
  "${COMPOSE[@]}" ps
}

cmd_logs() {
  require_docker
  local follow="" svc=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -f) follow="-f" ;;
      -n) [ $# -ge 2 ] || die "-n needs a value"; local tail="$2"; shift ;;
      *)  svc="$(resolve "$1")" ;;
    esac
    shift
  done
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" logs ${follow} ${tail:+--tail "$tail"} ${svc}
}

cmd_shell() {
  require_docker
  [ $# -ge 1 ] || die "usage: tradey shell <service>"
  local svc; svc="$(resolve "$1")"
  "${COMPOSE[@]}" exec "${svc}" sh
}

# Open the interactive VidIngest console (Spring Shell CLI).
cmd_cli() {
  require_docker
  info "ensuring vidingest-cli is up"
  "${COMPOSE[@]}" up -d vidingest-cli
  # The container idles; launch the actual shell via its launcher.
  "${COMPOSE[@]}" exec vidingest-cli vidingest-cli || \
    "${COMPOSE[@]}" exec vidingest-cli sh
}

# Run the ollama CLI inside the ollama container (e.g. pull/list models).
cmd_ollama() {
  require_docker
  "${COMPOSE[@]}" up -d ollama >/dev/null
  if [ $# -eq 0 ]; then
    "${COMPOSE[@]}" exec ollama ollama list
  else
    "${COMPOSE[@]}" exec ollama ollama "$@"
  fi
}

cmd_down() {
  require_docker
  local wipe=""
  for a in "$@"; do case "$a" in --volumes|-v) wipe=1 ;; *) die "unknown down flag: $a" ;; esac; done
  if [ -n "${wipe}" ]; then
    [ -t 0 ] || die "'down --volumes' needs an interactive terminal to confirm"
    printf "remove ALL named volumes (db, ollama models, video data, logs)? irreversible [y/N] "
    read -r ans; case "${ans}" in y|Y|yes|YES) ;; *) info "aborted"; exit 1 ;; esac
    info "taking everything down (incl. volumes)"
    "${COMPOSE[@]}" down --volumes
    ok "down — volumes removed"
  else
    info "taking everything down"
    "${COMPOSE[@]}" down
    ok "down"
  fi
}

usage() {
  cat <<EOF
Tradey — VidIngest manager

Usage: ./scripts/tradey.sh <command> [service|group] [--build] [--tag TAG]

Commands:
  start [target] [--build]   start a service/group (default: all). --build rebuilds first.
  build [target]             build image(s) only
  stop  [target]             stop a service/group (default: all)
  restart [target] [--build] stop + start
  status | ps                compose ps with health
  logs [-f] [-n N] [svc]     container logs
  shell <service>            sh inside a running container
  cli                        open the VidIngest console (vidingest-cli)
  ollama [args...]           ollama CLI in the ollama container (no args: list)
  down [--volumes]           stop + remove everything; --volumes also wipes data
  help                       this text

Targets:
  groups:   all  infra  sidecars  backend(be)
  services: timescaledb(db) ollama whisper paddleocr-server diarize-asr
            vidingest(vi) vidingest-cli(cli) vidingest-mcp(mcp)

Env overrides: VK_IMAGE_TAG (default latest)  VK_BIND_ADDR (default 127.0.0.1)
               plus any port in compose/ports.env
EOF
}

cmd="${1:-help}"; [ $# -gt 0 ] && shift || true
case "${cmd}" in
  start)            cmd_start "$@" ;;
  build)            cmd_build "$@" ;;
  stop)             cmd_stop "${1:-all}" ;;
  restart)          cmd_restart "$@" ;;
  status|ps)        cmd_status ;;
  logs)             cmd_logs "$@" ;;
  shell)            cmd_shell "$@" ;;
  cli|console)      cmd_cli ;;
  ollama)           cmd_ollama "$@" ;;
  down)             cmd_down "$@" ;;
  help|-h|--help)   usage ;;
  *)                die "unknown command: ${cmd}$(printf '\n'; usage)" ;;
esac

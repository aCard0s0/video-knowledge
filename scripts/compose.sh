#!/bin/bash
# Thin `docker compose` wrapper that layers the split compose files for
# VidIngest. Runs from the repo root so build contexts resolve.
#
# Usage: ./scripts/compose.sh <docker-compose-args...>
#   ./scripts/compose.sh config
#   ./scripts/compose.sh up -d vidingest
#   ./scripts/compose.sh ps
#
# Prefer ./scripts/tradey.sh for day-to-day start/stop/logs.
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${BASE_DIR}"

# Canonical host-port map (overridable via the environment).
set -a
# shellcheck disable=SC1091
[ -f "${BASE_DIR}/compose/ports.env" ] && . "${BASE_DIR}/compose/ports.env"
set +a

COMPOSE_FILES=(
  "-f" "${BASE_DIR}/compose.yml"
  "-f" "${BASE_DIR}/compose/infra/infra.yml"
  "-f" "${BASE_DIR}/compose/services.yml"
  "-f" "${BASE_DIR}/compose/cli.yml"
  "-f" "${BASE_DIR}/compose/mcp.yml"
)

exec docker compose -p video-knowledge "${COMPOSE_FILES[@]}" "$@"

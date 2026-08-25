#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1; then
  echo "Missing required command: docker" >&2
  exit 1
fi

MERMAID_IMAGE="${MERMAID_IMAGE:-minlag/mermaid-cli}"

list_sources() {
  find docs -type f -path '*/diagrams/mermaid/*.mmd' | sort
}

render_one() {
  local src="$1"
  local dest
  dest="$(printf '%s' "$src" | sed 's|/diagrams/mermaid/|/diagrams/svg/|')"
  dest="${dest%.mmd}.svg"

  mkdir -p "$(dirname "$dest")"

  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -v "$(pwd)":/data \
    "$MERMAID_IMAGE" \
    -i "/data/$src" \
    -o "/data/$dest"
}

count=0
while IFS= read -r src; do
  [[ -n "$src" ]] || continue
  render_one "$src"
  count=$((count + 1))
done < <(list_sources)

echo "Rendered $count Mermaid diagram(s) to SVG."


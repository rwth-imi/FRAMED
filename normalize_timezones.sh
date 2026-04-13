#!/usr/bin/env bash
set -euo pipefail

REPLAY_ROOT="$1"

if [[ ! -d "$REPLAY_ROOT" ]]; then
  echo "Error: $REPLAY_ROOT is not a directory"
  exit 1
fi

echo "Normalizing timestamps under: $REPLAY_ROOT"

strip_frac='sub("\\.[0-9]+Z$"; "Z")'

find "$REPLAY_ROOT" -type f -name "*.jsonl" | while read -r file; do
  echo "→ Processing $file"

  pc_ts=$(jq -r "select(.deviceID==\"PC60FW\") | .timestamp | $strip_frac" "$file" | head -n 1 || true)
  oxy_ts=$(jq -r "select(.deviceID==\"Oxylog-3000-Plus-00\") | .timestamp | $strip_frac" "$file" | head -n 1 || true)

  if [[ -z "$pc_ts" || -z "$oxy_ts" ]]; then
    echo "  ⚠ Skipping (missing PC60FW or Oxylog timestamps)"
    continue
  fi

  offset=$(jq -n \
    --arg pc "$pc_ts" \
    --arg oxy "$oxy_ts" \
    '((($pc | fromdateiso8601) - ($oxy | fromdateiso8601)))'
  )

  echo "  → Detected PC60FW offset: ${offset}s"

  tmp="$(mktemp)"

  jq -c --argjson offset "$offset" '
    def strip_frac: sub("\\.[0-9]+Z$"; "Z");

    if .deviceID == "PC60FW" then
      .timestamp |= (
        strip_frac
        | fromdateiso8601
        | . - $offset
        | todateiso8601
      )
    else
      .
    end
  ' "$file" > "$tmp"

  mv "$tmp" "$file"
done

echo "✅ Timezone normalization complete."

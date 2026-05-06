#!/usr/bin/env bash
set -euo pipefail

REPLAY_ROOT="/home/nils/Documents/Entwicklung/safety-box/replay"
CONFIG="config/services.json"

# Backup original config
cp "$CONFIG" "$CONFIG.bak"

# Find all jsonl files (deterministic order)
mapfile -t FILES < <(find "$REPLAY_ROOT" -type f -name "*.jsonl" | sort)

echo "Found ${#FILES[@]} replay files"

for file in "${FILES[@]}"; do
  # Extract filename only
  base_file="$(basename "$file")"           # data_07.jsonl
  base_name="${base_file%.jsonl}"           # data_07
  out_file="${base_name}_replay.jsonl"      # data_07_replay.jsonl

  echo "======================================"
  echo "Running replay for: $file"
  echo "Output file: $out_file"
  echo "======================================"

  jq \
    --arg replayPath "$file" \
    --arg outFile "$out_file" \
    '
    # Update ReplayProtocol input file
    (.Devices[] | select(.id=="Replay") | .filePath) = $replayPath
    |
    # Update JsonlDispatcher output filename
    (.Dispatchers[] | select(.id=="Json-Lines") | .fileName) = $outFile
    ' \
    "$CONFIG.bak" > "$CONFIG"

  mvn -q exec:java

  echo "Finished replay for: $file"
done

# Restore original config
mv "$CONFIG.bak" "$CONFIG"

echo "All replays completed successfully."
``
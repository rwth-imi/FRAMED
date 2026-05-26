#!/usr/bin/env bash
set -euo pipefail

REPLAY_ROOT="/home/nils/Documents/Entwicklung/safety-box/replay"
CONFIG="config/services.json"

# Backup original config
cp "$CONFIG" "$CONFIG.bak"

# Find all jsonl files (deterministic order)
mapfile -t FILES < <(find "$REPLAY_ROOT" -type f -name "*.jsonl" | sort)

echo "Found ${#FILES[@]} replay files"

run_pass () {
  local atomic_value="$1"         # true/false
  local out_dir="$2"              # output/evaluation_atomic/ or output/evaluation_non_atomic/

  echo
  echo "######################################"
  echo "Starting pass: atomic=${atomic_value}"
  echo "Output path:  ${out_dir}"
  echo "######################################"
  echo

  mkdir -p "$out_dir"

  for file in "${FILES[@]}"; do
    base_file="$(basename "$file")"           # data_07.jsonl
    base_name="${base_file%.jsonl}"           # data_07
    out_file="${base_name}_replay.jsonl"      # data_07_replay.jsonl

    echo "======================================"
    echo "Replay input : $file"
    echo "Replay output: ${out_dir}${out_file}"
    echo "atomic       : ${atomic_value}"
    echo "======================================"

    jq \
      --arg replayPath "$file" \
      --arg outFile "$out_file" \
      --arg outDir "$out_dir" \
      --argjson atomic "$atomic_value" \
      '
      # Update ReplayProtocol input file
      (.Devices[] | select(.id=="Replay") | .filePath) = $replayPath
      |
      # Force atomic setting for ALL reactors
      (.Reactors[] |= (.atomic = $atomic))
      |
      # Update JsonlDispatcher output path + filename
      (.Dispatchers[] | select(.id=="Json-Lines") | .path) = $outDir
      |
      (.Dispatchers[] | select(.id=="Json-Lines") | .fileName) = $outFile
      ' \
      "$CONFIG.bak" > "$CONFIG"

    mvn -q exec:java

    echo "Finished replay for: $file"
  done
}

# Pass 1: atomic=true
#run_pass true "output/evaluation_atomic/"

# Pass 2: atomic=false
run_pass false "output/evaluation_non_atomic_parallel/"

# Restore original config
mv "$CONFIG.bak" "$CONFIG"

echo
echo "All replays completed successfully (atomic + non-atomic)."

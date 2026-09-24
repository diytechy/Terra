#!/bin/bash
# Generate + measure every survey's windows with the pack currently in RUN/packs.
# Usage: tools/run_set.sh <label> [run dir] [surveys...]
#   label     output name: terrain in data/gen/<label>/<survey>, reports in reports/<label>/<survey>
# Seeds come from each survey's JSON header.
set -e
LAB="$(cd "$(dirname "$0")/.." && pwd -W)"
LABEL="$1"; RUN="${2:-$LAB/run}"; shift 2 || true
SURVEYS="${@:-s1a s1b s2a s3a}"
JAVA=/c/JAVA/jdk-25.0.1/bin/java
PY="$LAB/.venv/Scripts/python"
for s in $SURVEYS; do
  seed=$("$PY" -c "import json;print(json.load(open(r'$LAB/data/$s/survey.json'))['seed'])")
  gen="$LAB/data/gen/$LABEL/$s"
  mkdir -p "$gen"
  (cd "$RUN" && "$JAVA" -Xmx6g -cp terra-cli.jar com.dfsek.terra.cli.harness.TerraceHarness generate \
      --seed "$seed" --windows "$LAB/data/$s/windows.txt" --out "$gen" --skip-existing 2>&1 \
      | grep -E "Window |Generated [0-9]+ chunks in|Exception|ERROR" | grep -v "Bubbles\|Failed to load config pack" || true)
  "$PY" "$LAB/tools/measure.py" "$gen" "$LAB/data/$s/windows.json" "$LAB/reports/$LABEL/$s" --label "$LABEL"
done

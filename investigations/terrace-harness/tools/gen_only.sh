#!/bin/bash
# Generate the standard windows (no measuring) with extra JVM flags / chunk order / threads.
# Usage: tools/gen_only.sh <label> <run dir> "<jvm flags>" <order> <threads> [areas...]
# Output: data/det/<label>/<area>/<window>/..., timing lines in data/det/<label>/timing.txt
set -e
LAB="$(cd "$(dirname "$0")/.." && pwd -W)"
LABEL="$1"; RUN="$2"; FLAGS="$3"; ORDER="$4"; THREADS="$5"; shift 5
AREAS="${@:-s1a s1b s2a s3a}"
PY="$LAB/.venv/Scripts/python"
mkdir -p "$LAB/data/det/$LABEL"
: > "$LAB/data/det/$LABEL/timing.txt"
for s in $AREAS; do
  seed=$("$PY" -c "import json;print(json.load(open(r'$LAB/data/$s/survey.json'))['seed'])")
  (cd "$LAB/$RUN" && /c/JAVA/jdk-25.0.1/bin/java -Xmx6g $FLAGS -cp terra-cli.jar com.dfsek.terra.cli.harness.TerraceHarness \
     generate --seed "$seed" --windows "$LAB/data/$s/windows.txt" --out "$LAB/data/det/$LABEL/$s" \
     --order "$ORDER" --threads "$THREADS" 2>&1 | grep "TIMING" | sed "s/^/$s /" >> "$LAB/data/det/$LABEL/timing.txt")
done

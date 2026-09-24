#!/bin/bash
# Refresh run/ (addons + CLI jar) from the Terra worktree, and run/packs/CHIMERA.zip from a pack checkout.
# Usage: tools/sync_run.sh [ORIGEN2 checkout, default C:/Projects/wt/ORIGEN2-terrace] [run dir, default run]
set -e
LAB="$(cd "$(dirname "$0")/.." && pwd -W)"
TERRA=/c/Projects/wt/Terra-terrace
PACKSRC="${1:-/c/Projects/wt/ORIGEN2-terrace}"
RUN="${2:-$LAB/run}"
mkdir -p "$RUN/addons" "$RUN/packs"
rm -rf "$RUN/addons"/*
cp -r "$TERRA/platforms/cli/run/addons/." "$RUN/addons/"
cp "$(ls -t "$TERRA"/platforms/cli/build/libs/Terra-cli-*-shaded.jar | head -1)" "$RUN/terra-cli.jar"
python "$LAB/tools/make_pack.py" "$PACKSRC" "$RUN/packs/CHIMERA.zip"

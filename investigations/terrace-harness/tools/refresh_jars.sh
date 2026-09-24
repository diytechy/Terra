#!/bin/bash
# Refresh addons + CLI jar of existing run dirs from the Terra worktree build (keeps their packs).
# Usage: tools/refresh_jars.sh run_a [run_b ...]
set -e
TERRA=/c/Projects/wt/Terra-terrace
for RUN in "$@"; do
  rm -rf "$RUN/addons" && mkdir -p "$RUN/addons"
  cp -r "$TERRA/platforms/cli/run/addons/." "$RUN/addons/"
  cp "$(ls -t "$TERRA"/platforms/cli/build/libs/Terra-cli-*-shaded.jar | head -1)" "$RUN/terra-cli.jar"
done

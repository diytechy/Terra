#!/bin/bash
# Generate one world with a Chunky order script on this Paper copy (port 25594).
# usage: run_world.sh WORLD ORDERFILE TAG [terra|vanilla] ["extra JVM flags"]
set -e
P=/c/Projects/BedrockLab/terrace/paper
cd $P
W=$1; ORDER=$2; TAG=$3; GEN=${4:-terra}; FLAGS=${5:-}
rm -rf "$P/$W"
sed -i "s/^level-name=.*/level-name=$W/; s/^level-seed=.*/level-seed=2403/" server.properties
python - "$W" "$GEN" <<'PY'
import sys, re
w, gen = sys.argv[1], sys.argv[2]
s = open('bukkit.yml').read()
s = s[:s.index('worlds:')] if 'worlds:' in s else s
if gen == 'terra':
    s += f"worlds:\n  {w}:\n    generator: Terra:CHIMERA\n"
open('bukkit.yml', 'w').write(s)
PY
sed "s/WORLD/$W/" $ORDER > /tmp/order-run-$W.txt
mkdir -p ../reports/paper
/c/Projects/BedrockLab/terrace/.venv/Scripts/python ../tools/mcdriver.py --cwd $P --log ../reports/paper/$TAG.log --script /tmp/order-run-$W.txt -- \
  "C:/JAVA/jdk-25.0.1/bin/java.exe" -Xms4G -Xmx8G $FLAGS -jar paper-26.2-129.jar --nogui | tail -1
rm -f plugins/Chunky/tasks/*

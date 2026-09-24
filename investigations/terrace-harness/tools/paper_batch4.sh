#!/bin/bash
# Batch 4 (jar 86b4452d0, terrain reuse): order test + performance, rotated.
cd /c/Projects/BedrockLab/terrace/paper
F="-Dterra.decoration.orderIndependent=true"
./run_world.sh fA  order-A.txt fA  terra "$F"
./run_world.sh fB  order-B.txt fB  terra "$F"
./run_world.sh rO1 order-P.txt rO1 terra "$F"
./run_world.sh rT1 order-P.txt rT1 terra
./run_world.sh rT2 order-P.txt rT2 terra
./run_world.sh rO2 order-P.txt rO2 terra "$F"
for w in rO1 rT1 rT2 rO2; do rm -rf "$w"; done

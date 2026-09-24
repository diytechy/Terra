#!/bin/bash
# Batch 5 (handle comparison): order check vs fA + performance, rotated.
cd /c/Projects/BedrockLab/terrace/paper
F="-Dterra.decoration.orderIndependent=true"
./run_world.sh gA  order-A.txt gA  terra "$F"
./run_world.sh sO1 order-P.txt sO1 terra "$F"
./run_world.sh sT1 order-P.txt sT1 terra
./run_world.sh sT2 order-P.txt sT2 terra
./run_world.sh sO2 order-P.txt sO2 terra "$F"
for w in sO1 sT1 sT2 sO2; do rm -rf "$w"; done

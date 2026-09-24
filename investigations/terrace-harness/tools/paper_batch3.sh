#!/bin/bash
# Batch 3 (jar f90e88224, terrain capture): order test + performance, rotated.
cd /c/Projects/BedrockLab/terrace/paper
F="-Dterra.decoration.orderIndependent=true"
./run_world.sh cA  order-A.txt cA  terra "$F"
./run_world.sh cB  order-B.txt cB  terra "$F"
./run_world.sh cA2 order-A.txt cA2 terra "$F"
./run_world.sh qO1 order-P.txt qO1 terra "$F"
./run_world.sh qT1 order-P.txt qT1 terra
./run_world.sh qT2 order-P.txt qT2 terra
./run_world.sh qO2 order-P.txt qO2 terra "$F"
for w in qO1 qT1 qT2 qO2; do rm -rf "$w"; done

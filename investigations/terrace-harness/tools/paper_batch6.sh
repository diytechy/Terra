#!/bin/bash
# Batch 6 (background prefetch): order check vs fA + performance, rotated.
cd /c/Projects/BedrockLab/terrace/paper
F="-Dterra.decoration.orderIndependent=true"
./run_world.sh hA  order-A.txt hA  terra "$F"
./run_world.sh hB  order-B.txt hB  terra "$F"
./run_world.sh uO1 order-P.txt uO1 terra "$F"
./run_world.sh uT1 order-P.txt uT1 terra
./run_world.sh uT2 order-P.txt uT2 terra
./run_world.sh uO2 order-P.txt uO2 terra "$F"
for w in uO1 uT1 uT2 uO2; do rm -rf "$w"; done

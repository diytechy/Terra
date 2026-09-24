#!/bin/bash
# Paper performance: 65x65 chunks (radius 512 around 3000,3000), today vs order-independent, rotated.
cd /c/Projects/BedrockLab/terrace/paper
F="-Dterra.decoration.orderIndependent=true"
./run_world.sh pT1 order-P.txt pT1 terra
./run_world.sh pO1 order-P.txt pO1 terra "$F"
./run_world.sh pO2 order-P.txt pO2 terra "$F"
./run_world.sh pT2 order-P.txt pT2 terra
for w in pT1 pO1 pO2 pT2; do rm -rf "$w"; done

#!/bin/bash
# Batch 2: order-independent decoration on Paper (jar 4680652a4), A / B / A again.
cd /c/Projects/BedrockLab/terrace/paper
F="-Dterra.decoration.orderIndependent=true"
./run_world.sh oiA  order-A.txt oiA  terra "$F"
./run_world.sh oiB  order-B.txt oiB  terra "$F"
./run_world.sh oiA2 order-A.txt oiA2 terra "$F"
./run_world.sh vA2  order-A.txt vA2  vanilla

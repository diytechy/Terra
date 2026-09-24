#!/bin/bash
# Batch 1: scope the Paper feature-order dependence (jar d71849de2 = cache fix + per-seed Dendry + flags).
cd /c/Projects/BedrockLab/terrace/paper
./run_world.sh tA1 order-A.txt tA1 terra
./run_world.sh tA2 order-A.txt tA2 terra
./run_world.sh tB  order-B.txt tB  terra
./run_world.sh vA  order-A.txt vA  vanilla
./run_world.sh vB  order-B.txt vB  vanilla
./run_world.sh nvA order-A.txt nvA terra "-Dterra.bukkit.vanillaDecorations=false"
./run_world.sh nvB order-B.txt nvB terra "-Dterra.bukkit.vanillaDecorations=false"
./run_world.sh nsA order-A.txt nsA terra "-Dterra.stages.skip=all"
./run_world.sh nsB order-B.txt nsB terra "-Dterra.stages.skip=all"

#!/bin/bash
# Rotated-order performance runs: generation (s1a+s3a windows, 7 threads) and a pipeline-only
# biome survey (4096x4096 at step 4, 7 threads) for configs A-D. Timings in data/det/perf*/.
LAB="$(cd "$(dirname "$0")/.." && pwd -W)"
L="-Dterra.pipeline.legacyCacheIndex=true"; N="-Dterra.pipeline.chunkScopedCache=false"
run() { # cfg rep
  case $1 in A) R=run_perf_pub; F="$L";; B) R=run_perf_pub; F="";; C) R=run_perf_seed; F="";; D) R=run_perf_pub; F="$N";; esac
  "$LAB/tools/gen_only.sh" perf$1_$2 $R "$F" rows 7 s1a s3a
  (cd "$LAB/$R" && /c/JAVA/jdk-25.0.1/bin/java -Xmx6g $F -cp terra-cli.jar com.dfsek.terra.cli.harness.TerraceHarness survey \
     --seed 7099699057166038826 --x0 -8192 --z0 4096 --size 4096 --step 4 --threads 7 --out "$LAB/data/det/survey_tmp" 2>&1 \
     | grep "Survey " >> "$LAB/data/det/perf$1_$2/survey.txt")
}
for c in A B C D; do run $c 1; done
for c in D C B A; do run $c 2; done
for c in B D A C; do run $c 3; done

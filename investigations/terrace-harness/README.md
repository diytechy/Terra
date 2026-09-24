# CHIMERA terrace-border harness

Measures biome-border terrain on **generated blocks** (terrace-blending.md §5): generates real
CHIMERA terrain with the Terra fork's CLI, reads the top solid block of every column back from
the `.mca` files, cuts transects across borders between two terraced biomes, and reports ravines,
cliffs, step matching, slivers/ledges and orphaned step edges, each against in-biome controls.

## Pieces

| Path | What |
|---|---|
| Terra `bedrock/terrace-harness` → `platforms/cli` | `TerraCLI` gains `--origin-x/--origin-z/--chunks/--no-features/--out` and writes full-height 1.18+ Anvil files. `com.dfsek.terra.cli.harness.TerraceHarness` has `survey` (coarse biome raster), `generate` (terrain windows + per-column surface-biome raster) and `probe` (per-column blend diagnostics). |
| Terra … `chunk-generator-noise-3d/…/debug/TerrainProbe.java` | Exact (un-interpolated) blend at one column: each contributor's own height, the blended height, both border biomes' own heights. |
| Terra … `config/noise/terrace/*`, `ChunkInterpolator` | Prototype shared terrace blend (`terrain.terrace`). |
| ORIGEN2 `bedrock/terrace-harness` | `biomeSpread.reliefFadeAtCellBorders` switch (P1) and `terrain.terrace` specs for EQ_MULTI_TERRACED_LAND / EQ_TERRACED_MOUNTAINS. |
| `tools/families.py` | Biome → terrain template (first-parent-wins), terraced families. → `data/families.json` |
| `tools/find_borders.py` | Picks 16×16-chunk windows on terraced-pair borders from a survey. |
| `tools/anvil.py` | Python reader for modern region files (top solid, fluid top). |
| `tools/measure.py` | Transect + 2D metrics per window, PNG per window. |
| `tools/aggregate.py` | Summary Markdown/JSON, `--compare` for before/after. |
| `tools/diagnose.py` | Probes the worst ravines and classifies the cause. |
| `tools/pair_compare.py`, `tools/compare_windows.py` | Variant comparison on one template pair; per-window interior/border diff. |
| `tools/sync_run.sh`, `tools/run_set.sh`, `tools/make_pack.py` | Build a run dir (addons + CLI jar + pack zip) and run generate+measure. |

## Rerun

```bash
cd /c/Projects/BedrockLab/terrace
# 0. build the fork (JDK 25) and a run dir with the pack from a CHIMERA checkout
(cd /c/Projects/wt/Terra-terrace && JAVA_HOME=/c/JAVA/jdk-25.0.1 ./gradlew --no-daemon :platforms:cli:shadowJar :platforms:cli:moveAddonsrun)
tools/sync_run.sh /c/Projects/wt/ORIGEN2-terrace run_mine
.venv/Scripts/python tools/families.py /c/Projects/wt/ORIGEN2-terrace data/families.json
# 1. survey + windows (already in data/s1a, s1b, s2a, s3a, mt_*)
(cd run_mine && /c/JAVA/jdk-25.0.1/bin/java -Xmx6g -cp terra-cli.jar com.dfsek.terra.cli.harness.TerraceHarness \
   survey --seed 12345 --x0 -4096 --z0 -4096 --size 8192 --step 16 --out ../data/s2a)
.venv/Scripts/python tools/find_borders.py data/s2a data/s2a/windows --windows 16 --per-pair 1 --prefix s2a_
# 2. generate + measure, 3. summary / compare, 4. diagnosis
tools/run_set.sh mine run_mine s1a s1b s2a s3a
.venv/Scripts/python tools/aggregate.py mine --compare baseline
.venv/Scripts/python tools/diagnose.py mine --worst 36 --per-window 3 --run run_mine
```

Python deps are in `.venv` (numpy, scipy, scikit-image, matplotlib, pillow, pyyaml).

## Outputs

`reports/<label>/summary.md` (+ `.json`), `reports/<label>/<area>/<window>.png` (hillshade, biome
borders, transects in red where ravine ≥ 4, six worst profiles), `reports/<label>/diagnosis*/`
(`diagnosis.md`, `diagnosis.png`, raw probe JSONL). Labels: `baseline` (ORIGEN2 main 8511c3c9),
`nocell` (P1), `shared` (P1 + shared terrace for MTL/TM).

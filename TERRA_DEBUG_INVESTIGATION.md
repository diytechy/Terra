# Terra World Creation — Delay & Crash Investigation Guide

_Generated 2026-02-28. Server: Paper 1.21.11-74, Terra 7.0.0-BETA+82ce1c16c, Multiverse-Core 5.4.0_

---

## Background

When using `/mvcreate` with a Terra generator through Multiverse, world creation can be
slow or crash the server. There are two distinct delay windows, each with different causes.

---

## Two Distinct Delay Windows

### Window 1 — Server Startup (Pack Compilation)

All packs are compiled **once at plugin enable**, in parallel. Load times from latest.log:

| Pack | Load Time |
|------|-----------|
| TARTARUS | 1,431 ms |
| REIMAGEND | 1,880 ms |
| OVERWORLD | 6,156 ms |
| EXPLORE_TEST | 16,098 ms ← dominant |
| **Total Terra startup** | **~30 seconds** |

Heavy phases (all eager — no lazy loading):
- Sampler compilation (ALL pack samplers compiled even if unused by active preset)
- Biome YAML loading (parallel streams)
- BiomeProvider pipeline construction

**Implication:** ORIGEN2's large shared math sampler library drives a high startup cost
regardless of which preset (CHIMERA/origen2/etc.) is selected in pack.yml.

### Window 2 — `/mvcreate` (First Chunk Generation)

Sequence when Multiverse creates a new Terra world:

1. `TerraBukkitPlugin.getDefaultWorldGenerator()` → constructs `NoiseChunkGenerator3D`
   (runs synchronously on the **main thread**)
2. Constructor immediately scans **every biome** to find max blend distance — not lazy
3. Minecraft prepares the spawn area (~400+ chunks in a radius)
4. Each chunk miss on `PipelineBiomeProvider`'s 64-entry Caffeine cache triggers a full
   `BiomeChunkImpl` construction — the **entire pipeline** (expand → smooth → replace →
   border stages) runs synchronously in the constructor
5. A new per-world `SamplerProvider` (1024-entry Caffeine cache) is allocated

**There is no lazy initialization anywhere in this path.**

---

## Likely Crash Cause: Memory Pressure

Server JVM flags: `-Xms2G -Xmx4G`

At the point `/mvcreate` is called:
- 4 packs already loaded and resident in heap
- New per-world SamplerProvider allocated (1024 Sampler3D entries)
- Spawn chunk burst: ~400+ chunks hit cache misses simultaneously
- BiomeChunkImpl + Sampler3D objects allocated for each miss

The combination can push the JVM to GC thrash or OOM before world preparation finishes.

---

## Existing Tools (No Code Changes Needed)

### Pack Load Timing — Already Active
Pack load time in milliseconds is printed to console/log at INFO level automatically.
No configuration needed. Check `logs/latest.log` for `"Loaded config pack ... in Xms"`.

### Terra Profiler — Enable in config.yml
File: `plugins/Terra/config.yml`

```yaml
debug:
  profiler: true
```

Emits per-phase timing at DEBUG level for every chunk generated via SLF4J.
Already wired into `NoiseChunkGenerator3D`:
- Source: `ProfilerImpl.java` (thread-local stack tracking)
- Coverage: `chunk_base_3d` phase and sub-phases

### DEBUG Log Level — Enable in Paper logging config
Add to Paper's `log4j2.xml`:

```xml
<Logger name="com.dfsek.terra" level="DEBUG" additivity="false">
    <AppenderRef ref="File"/>
</Logger>
```

Surfaces debug statements in `ConfigPackImpl`, `PipelineBiomeProvider`, and stage processors.

---

## Investigation Steps (Priority Order)

### Step 1 — Read Existing Crash Reports (Highest Value, No Setup)
```
C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\crash-reports\
C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\hs_err_*.log   (JVM-level crash files)
```
Stack trace will pinpoint whether the crash is:
- `OutOfMemoryError` → memory pressure confirmed
- `NullPointerException` → Terra logic bug
- JVM `hs_err` crash → native/JVM-level issue (often GC or JIT related)

### Step 2 — Add Heap Dump on OOM
Edit `START.bat`, add JVM flags:

```bat
java -Xms2G -Xmx4G ^
  -XX:+HeapDumpOnOutOfMemoryError ^
  -XX:HeapDumpPath=heap.hprof ^
  -jar paper-1.21.11-74.jar -nogui
```

Captures full heap state at crash moment. Analyze with Eclipse MAT or VisualVM.

### Step 3 — Add GC Logging
Edit `START.bat`, add JVM flags:

```bat
java -Xms2G -Xmx4G ^
  -Xlog:gc*:file=gc.log:time,uptime ^
  -jar paper-1.21.11-74.jar -nogui
```

Shows whether the JVM is GC-thrashing during world creation.
Look for: long GC pauses > 1s, or repeated Full GC cycles during `/mvcreate`.

### Step 4 — Enable Terra Profiler
Add to `plugins/Terra/config.yml`:
```yaml
debug:
  profiler: true
```
Run `/mvcreate`, then observe DEBUG log output for per-chunk phase timing.

### Step 5 — Reduce Sampler Cache Size
In `plugins/Terra/config.yml`:
```yaml
cache:
  sampler: 256   # default is 1024 — reduces per-world memory footprint
```
If this prevents the crash, memory pressure during world creation is confirmed.

### Step 6 — Isolate Pack Load as a Variable
Remove all non-ORIGEN2 packs from `plugins/Terra/packs/` temporarily, then retry
`/mvcreate`. If crash disappears: aggregate heap usage across 4 packs is the cause.

### Step 7 — Increase Heap (Removes Pressure as Confound)
Edit `START.bat`:
```bat
java -Xms4G -Xmx8G -jar paper-1.21.11-74.jar -nogui
```
If this prevents the crash, memory is definitively the cause.

---

## Key Source Files for Future Reference

| Component | File | Key Detail |
|-----------|------|------------|
| Pack load timing | `ConfigPackImpl.java:121,218` | `System.nanoTime()` start/end |
| Parallel pack loading | `ConfigRegistry.java:44` | Synchronized + parallel streams |
| Multiverse hook | `MultiverseGeneratorPluginHook.java` | Registers as `GeneratorPlugin` |
| World gen instantiation | `TerraBukkitPlugin.java:182` | `getDefaultWorldGenerator()` |
| Sampler cache (per-world) | `SamplerProvider.java:39` | 1024-entry Caffeine cache |
| Biome chunk pipeline | `BiomeChunkImpl.java:22-86` | No lazy init — full pipeline in constructor |
| Biome cache | `PipelineBiomeProvider.java:46` | 64-entry Caffeine cache |
| Terra profiler | `ProfilerImpl.java` | Thread-local stack, SLF4J output |

---

## Architecture Notes

- `ConfigPack` holds ONE `BiomeProvider` — no runtime preset switching
- All samplers compiled eagerly at pack load (even unused ones)
- `BiomeChunkImpl` construction is synchronous and not lazy
- Per-world `SamplerProvider` is NOT shared across worlds
- Biome provider pipeline cache: 64 entries (small — miss cost is high on spawn generation)

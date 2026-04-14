# Plan: Non-Blocking Stronghold Ring Position Computation

## Context

When a new world is created via Multiverse `mvcreate` with Terra as the generator, the server crashes/times out. The root cause is that stronghold ring positions are computed asynchronously via `CompletableFuture`, but the main thread immediately calls `.join()` on those futures, blocking until all 128 stronghold positions are resolved. Each position requires an expensive `findBiomeHorizontal()` biome search — which is far slower with Terra's complex biome pipeline than vanilla.

The call chain is:
1. Multiverse `CreateCommand` → `worldCreator.createWorld()` (main thread, synchronous)
2. Paper `CraftServer.createWorld()` → `new ServerLevel()` → `prepareLevel()` (main thread)
3. During chunk loading, `ConcentricRingsStructurePlacement.isPlacementChunk()` → `getRingPositionsFor()` → `.join()` **blocks main thread**

The async futures already exist — the fix is to stop blocking on them.

## Files to Modify

### 1. `ChunkGeneratorStructureState.java`
**Path:** `paper-server/src/minecraft/java/net/minecraft/world/level/chunk/ChunkGeneratorStructureState.java`

**Add a non-blocking query method** (after `getRingPositionsFor` at line 356):

```java
// Paper start - Non-blocking stronghold ring positions
public @Nullable List<ChunkPos> getRingPositionsForNonBlocking(final ConcentricRingsStructurePlacement placement) {
    this.ensureStructuresGenerated();
    CompletableFuture<List<ChunkPos>> result = this.ringPositions.get(placement);
    if (result == null) return null;
    return result.getNow(null);
}

public boolean areRingPositionsReady(final ConcentricRingsStructurePlacement placement) {
    this.ensureStructuresGenerated();
    CompletableFuture<List<ChunkPos>> result = this.ringPositions.get(placement);
    return result != null && result.isDone();
}
// Paper end - Non-blocking stronghold ring positions
```

`getRingPositionsFor()` with `.join()` is **kept as-is** for any callers that truly need blocking semantics. The new method uses `CompletableFuture.getNow(null)` which returns immediately — either with the computed list or `null` if still pending.

### 2. `ConcentricRingsStructurePlacement.java`
**Path:** `paper-server/src/minecraft/java/net/minecraft/world/level/levelgen/structure/placement/ConcentricRingsStructurePlacement.java`

**Change `isPlacementChunk` (line 84-87)** to use the non-blocking variant:

```java
@Override
protected boolean isPlacementChunk(final ChunkGeneratorStructureState generatorState, final int sourceX, final int sourceZ) {
    List<ChunkPos> positions = generatorState.getRingPositionsForNonBlocking(this); // Paper - Non-blocking stronghold ring positions
    return positions != null && positions.contains(new ChunkPos(sourceX, sourceZ));
}
```

This is the hot path during chunk generation. When positions aren't ready, `isPlacementChunk` returns `false` — the chunk simply won't get a stronghold. This is safe because:
- 128 strongholds across millions of possible chunks = negligible collision probability during the computation window
- Already handles `null` by returning `false` (existing logic)

### 3. `ChunkGenerator.java`
**Path:** `paper-server/src/minecraft/java/net/minecraft/world/level/chunk/ChunkGenerator.java`

**Change `getNearestGeneratedStructure` (lines 238-240)** to use non-blocking and return `null` instead of throwing:

```java
List<ChunkPos> positions = level.getChunkSource().getGeneratorState().getRingPositionsForNonBlocking(rings); // Paper - Non-blocking stronghold ring positions
if (positions == null) {
    return null; // Paper - positions still being computed, treat as "not found"
}
```

This serves `/locate` commands, treasure maps, and ender eyes. Returning `null` propagates as "structure not found" — all callers already handle this gracefully.

### 4. `LocateCommand.java`
**Path:** `paper-server/src/minecraft/java/net/minecraft/server/commands/LocateCommand.java`

**Add a pre-check in `locateStructure` (before line 102)** for a user-friendly message when stronghold positions are still computing:

```java
// Paper start - Non-blocking stronghold ring positions
ChunkGeneratorStructureState genState = serverLevel.getChunkSource().getGeneratorState();
for (Holder<Structure> structure : target) {
    for (StructurePlacement placement : genState.getPlacementsForStructure(structure)) {
        if (placement instanceof ConcentricRingsStructurePlacement rings && !genState.areRingPositionsReady(rings)) {
            source.sendFailure(Component.literal("Stronghold positions are still being computed. Please try again shortly."));
            return 0;
        }
    }
}
// Paper end - Non-blocking stronghold ring positions
```

Without this, `/locate stronghold` during computation would silently return "not found" — confusing for players. This gives an explicit message instead.

## What Does NOT Change

- **`ServerLevel` constructor (line 683):** `ensureStructuresGenerated()` is already called here, which kicks off the async futures. This stays — it's the "eager start" part of the approach.
- **`getRingPositionsFor()` with `.join()`:** Kept for backward compatibility. No current hot path will call it during world creation after these changes.
- **Cache system (lines 266-342):** The existing `stronghold_positions.dat` cache is untouched. When cache exists, `loadOrGenerateRingPositions` returns `CompletableFuture.completedFuture(cached)` — `getNow(null)` returns the value instantly. Zero behavior change for cached worlds.
- **Ender eyes / treasure maps / dolphins:** All already handle `null` from `findNearestMapStructure()`.

## Thread Safety

- `ringPositions` map is populated on the main thread during `generatePositions()` (called from `ServerLevel` constructor) before any chunk generation starts — no concurrent writes.
- `CompletableFuture.getNow()` and `.isDone()` are inherently thread-safe.
- No new synchronization primitives needed.

## Edge Case: Missed Stronghold

If a chunk that should contain a stronghold is generated before positions are ready, that stronghold is silently skipped. Probability: ~128 / total chunks generated during computation window. In practice this is near-zero since only spawn chunks load initially, and strongholds start at ring distance 1408+ blocks from origin. If this is a concern, a follow-up could re-check affected chunks once positions are ready, but this is likely unnecessary.

## Verification

1. **Build Paper** with the changes
2. **Set up Terra** with a complex biome pack (e.g., CHIMERA)
3. **Install Multiverse-Core**, run `mvcreate test NORMAL -g Terra:CHIMERA`
4. **Verify:** World creates without timeout/crash, server remains responsive
5. **Wait ~1-2 minutes**, then run `/locate structure minecraft:stronghold` — should find a stronghold
6. **Run `/locate` immediately** after creation — should see "positions still being computed" message
7. **Restart server** — verify cache loads instantly (check logs for "Loaded N cached stronghold positions")
8. **Verify vanilla worlds** still work identically (cache hit = instant, no behavior change)

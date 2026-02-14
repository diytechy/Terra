# CacheSampler Performance Investigation

## Summary

The original `CacheSampler` (`type: CACHE`) had drastically poor performance due to
a catastrophic hash collision bug in the cache key types, autoboxing overhead from
Caffeine's generics-based API, and unnecessary complexity. All issues have been
resolved by replacing the Caffeine-based implementation with a direct-mapped primitive
cache.


## Issue 1: Catastrophic Hash Collisions (FIXED)

### The Bug

`DoubleSeededVector2Key.hashCode()` and `DoubleSeededVector3Key.hashCode()` used:

```java
int code = (int) Double.doubleToLongBits(x);
```

The `(int)` cast truncates to the **lower 32 bits** of the IEEE 754 representation.
For all integer-valued doubles (which world coordinates are), the lower 32 bits are
**all zeros**:

```
 0.0 -> 0x0000000000000000 -> (int) = 0
 1.0 -> 0x3FF0000000000000 -> (int) = 0
 2.0 -> 0x4000000000000000 -> (int) = 0
 ...
16.0 -> 0x4030000000000000 -> (int) = 0
```

For a 16x16 chunk with a constant seed, **all 256 coordinate pairs hashed to the
same single bucket**, degrading every lookup from O(1) to O(n).

### Fix

The key types were updated to use `Double.hashCode(x)` (XORs upper and lower 32
bits). The CacheSampler itself was replaced entirely (see below), but the key types
remain fixed for any other code that may use them.


## Issue 2: Autoboxing Overhead (FIXED)

Caffeine's `LoadingCache<Key, Double>` requires boxed `Double`, causing an object
allocation on every cache miss and unboxing on every hit. This cannot be avoided
within Caffeine's generics-based API. Resolved by replacing Caffeine entirely.


## Issue 3: Single Shared Executor (FIXED — no longer applicable)

`CacheUtils.CACHE_EXECUTOR` was a single `newSingleThreadExecutor()` shared across
all CacheSampler instances. No longer relevant since the new implementation has no
async maintenance.


## Issue 4: 3D Initial Capacity (FIXED)

The old 3D cache allocated `initialCapacity(981504)` per thread via Caffeine. The
new implementation uses 131,072 slots (~3 MB per thread), a clean power-of-2 size.


## New Implementation: Direct-Mapped Primitive Cache

`CacheSampler` now uses parallel primitive arrays (`double[]`, `long[]`) in inner
classes (`DirectCache2D`, `DirectCache3D`), one per thread via `ThreadLocal`.

### Design

- **No autoboxing**: all storage is primitive arrays
- **No object allocation** after initial construction
- **O(1) guaranteed**: no collision chains; hash conflicts simply overwrite
- **No eviction bookkeeping**: no frequency sketches, access tracking, or async tasks
- **Zero-initialization guard**: when all key components (x, z, seed) are zero —
  matching array defaults — the cache is bypassed via a bitwise OR check:
  `(doubleToRawLongBits(x) | doubleToRawLongBits(z) | seed) == 0L`

### Hash Function

The hash is designed so that **all 256 coordinates within a single 16x16 chunk map
to distinct slots**, guaranteeing zero within-chunk collisions for integer coordinates.

**2D** (4096 slots = 2^12, covers 16 chunks):
```java
int lo = ((int) x & 0xF) | (((int) z & 0xF) << 4);  // bits 0-7: unique within chunk
int hi = ((int) x >> 4) ^ ((int) z >> 4) ^ (int) seed; // bits 8-11: chunk identity
int index = (lo | (hi << 8)) & 0xFFF;
```

**3D** (131072 slots = 2^17, covers one full chunk column):
```java
int index = ((int) x & 0xF)              // bits 0-3: chunk-local x
          | (((int) z & 0xF) << 4)       // bits 4-7: chunk-local z
          | (((int) y & 0x1FF) << 8);    // bits 8-16: y (9 bits, 512 block range)
index &= 0x1FFFF;
```

The lower 8 bits (x and z) are preserved by any mask >= 0xFF, so the within-chunk
guarantee holds for any table size >= 256.

### Memory Footprint

| Cache | Slots | Arrays | Per thread |
|-------|-------|--------|------------|
| 2D    | 4,096 | 3 x double[] + 1 x long[] = 4 arrays | ~131 KB |
| 3D    | 131,072 | 4 x double[] + 1 x long[] = 5 arrays | ~3 MB |

### Trade-offs

Hash conflicts evict the previous entry (like a CPU L1 cache) rather than storing
both. For chunk generation's sequential spatial access pattern, this works well —
recently-computed nearby coordinates are what you want cached, and that's what
naturally stays.

For non-integer coordinates (e.g., after frequency scaling), the integer truncation
in the hash means different fractional values with the same integer part collide.
The key comparison catches mismatches (a miss, not a wrong result), so correctness
is preserved; only hit rate is affected.


## Files Changed

- `CacheSampler.java` — Replaced Caffeine with direct-mapped primitive cache
- `DoubleSeededVector2Key.java` — Fixed hashCode (retained for other potential users)
- `DoubleSeededVector3Key.java` — Fixed hashCode (retained for other potential users)

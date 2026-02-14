# CacheSampler Performance Investigation

## Summary

The `CacheSampler` (`type: CACHE`) has drastically poor performance due to a
catastrophic hash collision bug in the cache key types, compounded by several
secondary issues.


## Issue 1: Catastrophic Hash Collisions (CRITICAL — FIXED)

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

The distinguishing information is entirely in the upper 32 bits, which were discarded.

### Impact

For a 16x16 chunk with a constant seed, **all 256 coordinate pairs hash to the same
single bucket**. This degrades every cache lookup from O(1) to O(n), where n is the
number of cached entries. The cache becomes a linear scan that adds overhead on top of
the noise computation it was meant to avoid.

### Fix Applied

Changed both key types to use `Double.hashCode(x)` which computes
`Long.hashCode(Double.doubleToLongBits(x))` — XORing the upper and lower 32 bits
together. This is a JVM-standard pattern with negligible cost (one XOR + one unsigned
shift per double component).

Before: 256 coordinate pairs -> 1 unique hash
After:  256 coordinate pairs -> 248 unique hashes

### Computational cost of hashing

`Double.hashCode(double)` internally does:
1. `Double.doubleToLongBits(x)` — JVM intrinsic, just reinterprets bits (free)
2. `(int)(bits ^ (bits >>> 32))` — one XOR, one shift

This is about as cheap as a hash can get. More sophisticated hash functions
(MurmurHash3, xxHash) provide marginally better distribution but are unnecessary
here since the input coordinates are already well-distributed once the full 64 bits
are used.

### Files changed

- `common/api/src/main/java/com/dfsek/terra/api/util/cache/DoubleSeededVector2Key.java`
- `common/api/src/main/java/com/dfsek/terra/api/util/cache/DoubleSeededVector3Key.java`


## Issue 2: Autoboxing Overhead (NOT YET FIXED)

### The Problem

`LoadingCache<DoubleSeededVector2Key, Double>` uses boxed `Double`, not primitive
`double`. Every cache miss boxes the computed value (`double` -> `Double` object
allocation), and every hit unboxes it. With millions of evaluations per chunk
generation, this creates significant GC pressure.

### Why it can't be fixed within Caffeine

Caffeine uses Java generics (`Cache<K, V>`), which require object types. There is no
`Cache<K, double>` variant. This is a fundamental limitation of Caffeine's API and
Java's type erasure. No configuration change can avoid the boxing.

### Recommended fix: Replace Caffeine with a direct-mapped primitive cache

For this specific use case (fixed-size, thread-local, coordinate-keyed noise cache),
Caffeine is overkill. A direct-mapped cache using parallel primitive arrays would
eliminate all overhead:

```java
// Conceptual design for 2D:
public class DirectSamplerCache2D {
    private final int mask;          // capacity - 1 (capacity must be power of 2)
    private final double[] keyX;
    private final double[] keyZ;
    private final long[] keySeed;
    private final double[] values;
    private final boolean[] occupied;

    public double getOrCompute(Sampler sampler, long seed, double x, double z) {
        int hash = Double.hashCode(x);
        hash = 31 * hash + Double.hashCode(z);
        hash = 31 * hash + Long.hashCode(seed);
        int index = hash & mask;

        if (occupied[index]
            && keyX[index] == x
            && keyZ[index] == z
            && keySeed[index] == seed) {
            return values[index];  // Cache hit - no boxing
        }

        double value = sampler.getSample(seed, x, z);
        keyX[index] = x;
        keyZ[index] = z;
        keySeed[index] = seed;
        values[index] = value;
        occupied[index] = true;
        return value;  // No boxing
    }
}
```

Benefits over Caffeine for this use case:
- Zero autoboxing (all primitive arrays)
- Zero object allocation after construction
- O(1) guaranteed (no collision chains — conflicts simply overwrite)
- No frequency sketch / access tracking overhead
- Cache-line friendly memory layout
- Thread-safe when used with ThreadLocal (which CacheSampler already does)

Trade-off: Hash conflicts evict the previous entry (like a CPU L1 cache) rather
than maintaining multiple entries per bucket. For noise evaluation with spatial
locality (chunk generation iterates coordinates sequentially), this works well.
The hit rate depends on access patterns, but the elimination of all overhead makes
even a lower hit rate worthwhile.


## Issue 3: Single Shared Executor (minor)

`CacheUtils.CACHE_EXECUTOR` is a single `Executors.newSingleThreadExecutor()` shared
across ALL CacheSampler instances and all threads. Caffeine uses this for async
maintenance (eviction, cleanup). With many cache instances, all maintenance is
serialized through this one thread.

This becomes moot if Caffeine is replaced with a direct-mapped cache (which needs
no async maintenance).


## Issue 4: 3D Initial Capacity (minor)

The 3D cache allocates `initialCapacity(981504)` per thread. This is a large upfront
allocation even if most entries go unused. A direct-mapped cache with a power-of-2
capacity (e.g., 65536 or 131072) would use predictable, bounded memory.


## Key Source Files

- `CacheSampler.java` — The cache wrapper sampler
- `DoubleSeededVector2Key.java` — 2D cache key (hashCode FIXED)
- `DoubleSeededVector3Key.java` — 3D cache key (hashCode FIXED)
- `CacheUtils.java` — Shared single-thread executor
- `CacheSamplerTemplate.java` — YAML config template for CACHE type

package com.dfsek.terra.addons.biome.pipeline.pipeline;

import com.dfsek.seismic.type.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.dfsek.terra.addons.biome.pipeline.api.BiomeChunk;
import com.dfsek.terra.addons.biome.pipeline.api.Expander;
import com.dfsek.terra.addons.biome.pipeline.api.Pipeline;
import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;
import com.dfsek.terra.addons.biome.pipeline.cache.ChunkGenerationContext;
import com.dfsek.terra.addons.biome.pipeline.cache.ChunkScopedCacheSampler;
import com.dfsek.terra.addons.biome.pipeline.cache.PipelineSamplerAnalysis;
import com.dfsek.terra.api.profiler.Profiler;
import com.dfsek.terra.api.util.cache.SeededVector2Key;


public class PipelineImpl implements Pipeline {

    private static final Logger logger = LoggerFactory.getLogger(PipelineImpl.class);

    private final Source source;
    private final List<Stage> stages;
    private final int chunkSize;
    private final int expanderCount;
    private final int arraySize;
    private final int chunkOriginArrayIndex;
    private final int resolution;
    private final Profiler profiler;
    private final ThreadLocal<ChunkGenerationContext> chunkContextLocal;
    private final int numCachedSamplers;

    public PipelineImpl(Source source, List<Stage> stages, int resolution, int maxArraySize, Profiler profiler) {
        this(source, stages, resolution, maxArraySize, profiler, null, null, false);
    }

    /**
     * Full constructor with optional sampler caching support.
     *
     * @param source the biome source
     * @param stages the pipeline stages
     * @param resolution the pipeline resolution
     * @param maxArraySize maximum array size
     * @param profiler the profiler
     * @param packSamplers map of pack-level samplers for caching analysis (null = no caching)
     * @param complexityEstimator function to estimate sampler complexity (required if packSamplers is not null)
     * @param debugProfiler whether to enable debug logging for sampler caching analysis
     */
    public PipelineImpl(
        Source source,
        List<Stage> stages,
        int resolution,
        int maxArraySize,
        Profiler profiler,
        Map<String, Sampler> packSamplers,
        Function<Sampler, Integer> complexityEstimator,
        boolean debugProfiler) {

        this.source = source;
        this.stages = stages;
        this.resolution = resolution;
        this.profiler = profiler;
        this.expanderCount = (int) stages.stream().filter(s -> s instanceof Expander).count();

        int chunkOriginArrayIndex = BiomeChunkImpl.calculateChunkOriginArrayIndex(expanderCount, stages);

        // Find the largest initialSize whose post-expansion array fits within maxArraySize
        int bestInitialSize = 1;
        int bestArraySize = BiomeChunkImpl.initialSizeToArraySize(expanderCount, 1);
        int initialSize = 2;
        while(true) {
            int candidateArraySize = BiomeChunkImpl.initialSizeToArraySize(expanderCount, initialSize);
            if(candidateArraySize > maxArraySize) break;
            bestInitialSize = initialSize;
            bestArraySize = candidateArraySize;
            initialSize++;
        }

        int chunkSize = BiomeChunkImpl.calculateChunkSize(bestArraySize, chunkOriginArrayIndex, expanderCount);
        if(chunkSize < 1) {
            // Stages consume more border than the max array size allows — fall back to minimum viable size
            while(chunkSize < 1) {
                bestInitialSize++;
                bestArraySize = BiomeChunkImpl.initialSizeToArraySize(expanderCount, bestInitialSize);
                chunkSize = BiomeChunkImpl.calculateChunkSize(bestArraySize, chunkOriginArrayIndex, expanderCount);
            }
            if (debugProfiler) logger.warn("Pipeline array size {} exceeds maximum {} due to stage border requirements", bestArraySize, maxArraySize);
        }

        this.arraySize = bestArraySize;
        this.chunkOriginArrayIndex = chunkOriginArrayIndex;
        this.chunkSize = chunkSize;

        // Initialize caching context and analyze pack samplers
        this.chunkContextLocal = new ThreadLocal<>();
        if (packSamplers != null && !packSamplers.isEmpty() && complexityEstimator != null) {
            PipelineSamplerAnalysis.AnalysisResult analysisResult =
                PipelineSamplerAnalysis.analyze(source, stages, packSamplers, bestArraySize, complexityEstimator, debugProfiler);

            // Install chunk-scope cache wrappers on selected samplers
            for (PipelineSamplerAnalysis.SelectedSampler selected : analysisResult.selected) {
                Sampler selectedSampler = selected.sampler;

                // Unwrap DimensionApplicableSampler to get the LastValueSampler inside
                // (Check by class simple name to avoid cross-addon dependency)
                Sampler sampler = selectedSampler;
                if (selectedSampler.getClass().getSimpleName().equals("DimensionApplicableSampler")) {
                    sampler = unwrapDimensionApplicableSampler(selectedSampler);
                    if (sampler == null) {
                        if (debugProfiler) logger.warn("Could not unwrap DimensionApplicableSampler for {}", selected.name);
                        continue;
                    }
                }

                // Get the inner sampler that the LastValueSampler currently wraps
                // (LastValueSampler wraps DeferredExpressionSampler or other compiled samplers)
                Sampler innerSampler = getLastValueSamplerDelegate(sampler);
                if (innerSampler == null) {
                    // If we can't get the delegate, skip this sampler
                    if (debugProfiler) logger.warn("Could not extract delegate from LastValueSampler for {}", selected.name);
                    continue;
                }

                // Wrap the inner sampler in the cache
                ChunkScopedCacheSampler cacheSampler = new ChunkScopedCacheSampler(innerSampler, chunkContextLocal, selected.slot);

                // Replace the delegate in the LastValueSampler with the cache wrapper
                // Now: DimensionApplicableSampler → LastValueSampler → ChunkScopedCacheSampler → (original inner sampler)
                setLastValueSamplerDelegate(sampler, cacheSampler);
            }
            this.numCachedSamplers = analysisResult.numSlots;
        } else {
            this.numCachedSamplers = 0;
        }

        if (debugProfiler) {
            logger.info("Initialized a new biome pipeline:");
            logger.info("Array size: {} (Max: {})", bestArraySize, maxArraySize);
            logger.info("Internal array origin: {}", chunkOriginArrayIndex);
            logger.info("Chunk size: {}", chunkSize);
            logger.info("Expander count: {}", expanderCount);
            logger.info("Resolution: {}", resolution);
        }
    }

    @Override
    public BiomeChunk generateChunk(SeededVector2Key worldCoordinates) {
        // Skip context setup if no samplers are cached
        if (numCachedSamplers == 0) {
            return new BiomeChunkImpl(worldCoordinates, this);
        }

        // Get or create the context for this thread
        ChunkGenerationContext ctx = chunkContextLocal.get();
        if (ctx == null) {
            ctx = new ChunkGenerationContext(numCachedSamplers, arraySize);
            chunkContextLocal.set(ctx);
        }

        // Set up context for this chunk
        int blockOriginX = (worldCoordinates.x - chunkOriginArrayIndex) * resolution;
        int blockOriginZ = (worldCoordinates.z - chunkOriginArrayIndex) * resolution;
        ctx.reset(blockOriginX, blockOriginZ, resolution);

        try {
            return new BiomeChunkImpl(worldCoordinates, this);
        } finally {
            // Invalidate the context so stale cache entries aren't reused between chunks
            ctx.invalidate();
        }
    }

    @Override
    public int getChunkSize() {
        return chunkSize;
    }

    @Override
    public Source getSource() {
        return source;
    }

    @Override
    public List<Stage> getStages() {
        return stages;
    }

    protected int getExpanderCount() {
        return expanderCount;
    }

    protected int getArraySize() {
        return arraySize;
    }

    protected int getChunkOriginArrayIndex() {
        return chunkOriginArrayIndex;
    }

    protected int getResolution() {
        return resolution;
    }

    protected Profiler getProfiler() {
        return profiler;
    }

    /**
     * Extract the delegate from a LastValueSampler wrapper via reflection.
     * Returns the inner sampler that the LastValueSampler currently delegates to.
     */
    /**
     * Unwrap a DimensionApplicableSampler to get the inner sampler via reflection.
     * DimensionApplicableSampler wraps a single Sampler and stores it in a private field.
     * This method extracts that inner sampler without requiring a direct type reference.
     */
    private static Sampler unwrapDimensionApplicableSampler(Sampler dimensionApplicable) {
        try {
            var method = dimensionApplicable.getClass().getMethod("getSampler");
            Object result = method.invoke(dimensionApplicable);
            if (result instanceof Sampler) {
                return (Sampler) result;
            }
        } catch (Exception e) {
            logger.warn("Failed to unwrap DimensionApplicableSampler", e);
        }
        return null;
    }

    private static Sampler getLastValueSamplerDelegate(Sampler lastValueSampler) {
        try {
            var method = lastValueSampler.getClass().getMethod("getDelegate");
            Object result = method.invoke(lastValueSampler);
            if (result instanceof Sampler) {
                return (Sampler) result;
            }
        } catch (Exception e) {
            logger.warn("Failed to get LastValueSampler delegate", e);
        }
        return null;
    }

    /**
     * Helper to set the delegate on a LastValueSampler wrapper via reflection.
     * This allows swapping in a ChunkScopedCacheSampler while the LastValueSampler
     * is held by compiled expressions (which can't be changed).
     */
    private static void setLastValueSamplerDelegate(Sampler lastValueSampler, Sampler newDelegate) {
        try {
            var method = lastValueSampler.getClass().getDeclaredMethod("setDelegate", Sampler.class);
            method.setAccessible(true);
            method.invoke(lastValueSampler, newDelegate);
        } catch (Exception e) {
            logger.warn("Failed to set LastValueSampler delegate", e);
        }
    }
}

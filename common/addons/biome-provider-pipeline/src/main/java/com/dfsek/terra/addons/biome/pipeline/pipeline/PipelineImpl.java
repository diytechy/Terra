package com.dfsek.terra.addons.biome.pipeline.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import com.dfsek.terra.addons.biome.pipeline.api.BiomeChunk;
import com.dfsek.terra.addons.biome.pipeline.api.Expander;
import com.dfsek.terra.addons.biome.pipeline.api.Pipeline;
import com.dfsek.terra.addons.biome.pipeline.api.Source;
import com.dfsek.terra.addons.biome.pipeline.api.Stage;
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

    public PipelineImpl(Source source, List<Stage> stages, int resolution, int maxArraySize, Profiler profiler) {
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
            logger.warn("Pipeline array size {} exceeds maximum {} due to stage border requirements", bestArraySize, maxArraySize);
        }

        this.arraySize = bestArraySize;
        this.chunkOriginArrayIndex = chunkOriginArrayIndex;
        this.chunkSize = chunkSize;

        logger.info("Initialized a new biome pipeline:");
        logger.info("Array size: {} (Max: {})", bestArraySize, maxArraySize);
        logger.info("Internal array origin: {}", chunkOriginArrayIndex);
        logger.info("Chunk size: {}", chunkSize);
        logger.info("Expander count: {}", expanderCount);
        logger.info("Resolution: {}", resolution);
    }

    @Override
    public BiomeChunk generateChunk(SeededVector2Key worldCoordinates) {
        return new BiomeChunkImpl(worldCoordinates, this);
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
}

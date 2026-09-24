/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the common/api directory.
 */

package com.dfsek.terra.api.world.chunk.generation;

import java.util.function.Consumer;

import com.dfsek.terra.api.world.WritableWorld;


/**
 * A world view in which operations on the live world (block entities, entities) cannot run yet.
 * <p>
 * Order-independent decoration computes a chunk's features against a private copy of the terrain
 * and applies the result later, chunk by chunk. Operations that need the live world, such as
 * filling a container's loot, applying block-entity state or spawning an entity, are handed over
 * with {@link #defer} and run against the live world when the chunk containing ({@code x, y, z})
 * is written. Callers must check {@code world instanceof DeferringWorld} and defer instead of
 * acting on the view directly.
 */
public interface DeferringWorld {
    void defer(int x, int y, int z, Consumer<WritableWorld> operation);
}

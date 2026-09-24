/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra Core Addons are licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in this module's root directory.
 */

package com.dfsek.terra.addons.terrascript.script.functions;

import com.dfsek.seismic.math.floatingpoint.FloatingPointFunctions;
import com.dfsek.seismic.type.vector.Vector2;
import com.dfsek.seismic.type.vector.Vector3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dfsek.terra.addons.terrascript.parser.lang.ImplementationArguments;
import com.dfsek.terra.addons.terrascript.parser.lang.Returnable;
import com.dfsek.terra.addons.terrascript.parser.lang.Scope;
import com.dfsek.terra.addons.terrascript.parser.lang.functions.Function;
import com.dfsek.terra.addons.terrascript.script.TerraImplementationArguments;
import com.dfsek.terra.addons.terrascript.tokenizer.Position;
import com.dfsek.terra.api.block.entity.BlockEntity;
import com.dfsek.terra.api.world.WritableWorld;
import com.dfsek.terra.api.world.chunk.generation.DeferringWorld;


public class StateFunction implements Function<Void> {
    private static final Logger LOGGER = LoggerFactory.getLogger(StateFunction.class);
    private final Returnable<String> data;
    private final Returnable<Number> x, y, z;
    private final Position position;

    public StateFunction(Returnable<Number> x, Returnable<Number> y, Returnable<Number> z, Returnable<String> data,
                         Position position) {
        this.position = position;
        this.data = data;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public Void apply(ImplementationArguments implementationArguments, Scope scope) {
        TerraImplementationArguments arguments = (TerraImplementationArguments) implementationArguments;
        Vector2 xz = Vector2.Mutable.of(x.apply(implementationArguments, scope).doubleValue(),
            z.apply(implementationArguments, scope).doubleValue()).rotate(arguments.getRotation());


        Vector3 origin = Vector3.of(FloatingPointFunctions.round(xz.getX()), y.apply(implementationArguments, scope).intValue(),
            FloatingPointFunctions.round(xz.getZ())).mutable().add(arguments.getOrigin().toFloat()).immutable();
        String stateData = data.apply(implementationArguments, scope);
        WritableWorld world = arguments.getWorld();
        if(world instanceof DeferringWorld deferring) {
            // Order-independent decoration: apply once the owning chunk is written.
            deferring.defer(origin.getFloorX(), origin.getFloorY(), origin.getFloorZ(), live -> applyState(live, origin, stateData));
        } else {
            applyState(world, origin, stateData);
        }
        return null;
    }

    @Override
    public Position getPosition() {
        return position;
    }

    @Override
    public ReturnType returnType() {
        return ReturnType.VOID;
    }

    private static void applyState(WritableWorld world, Vector3 origin, String stateData) {
        try {
            BlockEntity state = world.getBlockEntity(origin);
            state.applyState(stateData);
            state.update(false);
        } catch(Exception e) {
            LOGGER.warn("Could not apply BlockState at {}", origin, e);
            e.printStackTrace();
        }
    }
}

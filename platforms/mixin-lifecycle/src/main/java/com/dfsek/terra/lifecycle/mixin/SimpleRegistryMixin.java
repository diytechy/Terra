package com.dfsek.terra.lifecycle.mixin;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Holder.Reference;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

import com.dfsek.terra.lifecycle.util.RegistryHack;


@Mixin(MappedRegistry.class)
public class SimpleRegistryMixin<T> implements RegistryHack {
    @Shadow
    @Final
    private Map<T, Reference<T>> valueToEntry;

    @Override
    public void terra_bind() {
        valueToEntry.forEach((value, entry) -> {
            //noinspection unchecked
            ((RegistryEntryReferenceInvoker<T>) entry).invokeSetValue(value);
        });
    }
}

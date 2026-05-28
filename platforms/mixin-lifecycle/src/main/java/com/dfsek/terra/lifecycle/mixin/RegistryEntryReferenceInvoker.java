package com.dfsek.terra.lifecycle.mixin;


import net.minecraft.core.Holder.Reference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;


@Mixin(Reference.class)
public interface RegistryEntryReferenceInvoker<T> {
    @Invoker("setValue")
    void invokeSetValue(T value);
}

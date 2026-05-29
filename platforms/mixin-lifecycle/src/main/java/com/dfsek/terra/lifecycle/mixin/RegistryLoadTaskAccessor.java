package com.dfsek.terra.lifecycle.mixin;

import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryLoadTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


/**
 * 26.1 encapsulates each dynamic registry's writable {@code MappedRegistry} inside a
 * {@link RegistryLoadTask} (private {@code registry} field) rather than exposing a
 * {@code List<WritableRegistry<?>>}. This accessor lets {@code RegistryLoaderMixin} pull the
 * still-unfrozen writable registry out of a task before it is frozen.
 */
@Mixin(RegistryLoadTask.class)
public interface RegistryLoadTaskAccessor {
    @Accessor("registry")
    WritableRegistry<?> terra_getRegistry();
}

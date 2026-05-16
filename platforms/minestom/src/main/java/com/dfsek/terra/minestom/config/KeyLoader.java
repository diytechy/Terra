package com.dfsek.terra.minestom.config;

import com.dfsek.tectonic.api.exception.LoadException;
import com.dfsek.tectonic.api.loader.ConfigLoader;
import com.dfsek.tectonic.api.loader.type.TypeLoader;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.AnnotatedType;


public class KeyLoader implements TypeLoader<Key> {
    @Override
    public Key load(
        @NotNull AnnotatedType annotatedType,
        @NotNull Object o,
        @NotNull ConfigLoader configLoader
    ) throws LoadException {
        if(!(o instanceof @Subst("a:o")String stringKey)) {
            throw new LoadException("Value is not a String", ConfigLoader.CURRENT_DEPTH.get());
        }
        try {
            return Key.key(stringKey);
        } catch(InvalidKeyException e) {
            throw new LoadException("Can't load key: Invalid Format", e, ConfigLoader.CURRENT_DEPTH.get());
        }
    }
}

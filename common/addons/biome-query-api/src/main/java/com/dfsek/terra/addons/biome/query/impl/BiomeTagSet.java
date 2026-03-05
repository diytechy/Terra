package com.dfsek.terra.addons.biome.query.impl;

import java.util.Set;

import com.dfsek.terra.api.properties.Properties;


public class BiomeTagSet implements Properties {
    private final Set<String> tags;

    public BiomeTagSet(Set<String> tags) {
        this.tags = tags;
    }

    public boolean contains(String tag) {
        return tags.contains(tag);
    }

    public Set<String> getTags() {
        return tags;
    }
}

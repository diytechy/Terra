package com.dfsek.terra.addons.biome.query.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


public class BiomeTagFlattener {
    private final Map<String, Integer> tagIndices;

    public BiomeTagFlattener(Collection<String> tags) {
        this.tagIndices = new HashMap<>();
        for(String tag : tags) {
            tagIndices.putIfAbsent(tag, tagIndices.size());
        }
    }

    public int index(String tag) {
        Integer idx = tagIndices.get(tag);
        if(idx == null) {
            throw new IllegalArgumentException(
                "Unknown biome tag '" + tag + "'. No biome in this pack defines this tag. " +
                "Check extrusion 'from' fields in your biome provider config.");
        }
        return idx;
    }

    public boolean contains(String tag) {
        return tagIndices.containsKey(tag);
    }

    public int size() {
        return tagIndices.size();
    }
}

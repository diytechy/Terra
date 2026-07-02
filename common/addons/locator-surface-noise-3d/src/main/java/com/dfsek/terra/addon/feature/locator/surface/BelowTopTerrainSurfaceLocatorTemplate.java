package com.dfsek.terra.addon.feature.locator.surface;

import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;

import com.dfsek.terra.api.config.meta.Meta;
import com.dfsek.terra.api.structure.feature.Locator;


@SuppressWarnings({ "FieldCanBeLocal", "FieldMayBeFinal" })
public class BelowTopTerrainSurfaceLocatorTemplate implements ObjectTemplate<Locator> {

    @Value("offset")
    @Default
    private @Meta int offset = 0;

    @Override
    public Locator get() {
        return new BelowTopTerrainSurfaceLocator(offset);
    }
}

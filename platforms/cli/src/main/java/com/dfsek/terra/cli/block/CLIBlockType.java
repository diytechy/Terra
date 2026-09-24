package com.dfsek.terra.cli.block;

import com.dfsek.terra.api.block.BlockType;
import com.dfsek.terra.api.block.state.BlockState;
import com.dfsek.terra.api.util.generic.Lazy;


public class CLIBlockType implements BlockType {
    private final String value;
    private final boolean solid;
    private final boolean water;
    private final Lazy<CLIBlockState> defaultState;

    public CLIBlockType(String value) {
        if(value.contains("[")) throw new IllegalArgumentException("Block Type must not contain properties");
        this.value = value;
        this.solid = isSolidName(value);
        this.water = value.equals("minecraft:water");
        this.defaultState = Lazy.lazy(() -> new CLIBlockState(value));
    }

    /**
     * Approximation of Bukkit's Material#isSolid (blocks that stop movement): air, fluids, plants,
     * vines, snow layers, torches, rails and similar are not solid. Needed so CLI feature decoration
     * follows the same locator rules as the server.
     */
    static boolean isSolidName(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if(n.endsWith("air") || n.equals("water") || n.equals("lava") || n.equals("bubble_column") || n.equals("light")) return false;
        if(n.equals("snow") || n.equals("cobweb")) return false;
        return !n.matches(".*(grass|fern|flower|sapling|vine|dandelion|poppy|orchid|allium|bluet|tulip|daisy|cornflower|lily_of_the_valley"
                          + "|wither_rose|torchflower|pitcher|dead_bush|seagrass|kelp|sugar_cane|bamboo_sapling|sweet_berry_bush|mushroom$"
                          + "|fungus$|roots$|sprouts|lichen|hanging_moss|spore_blossom|dripleaf_stem|small_dripleaf|glow_berries"
                          + "|cave_vines|torch|rail|lever|button|pressure_plate|redstone_wire|tripwire|carpet|coral$|coral_fan|sea_pickle"
                          + "|frogspawn|pink_petals|wildflowers|leaf_litter|firefly_bush|bush$|short_dry_grass|tall_dry_grass|eyeblossom).*")
               || n.endsWith("_block") || n.endsWith("mushroom_block");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CLIBlockType t && t.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String getHandle() {
        return value;
    }

    @Override
    public BlockState getDefaultState() {
        return defaultState.value();
    }

    @Override
    public boolean isSolid() {
        return solid;
    }

    @Override
    public boolean isWater() {
        return water;
    }
}

package com.dfsek.terra.bukkit.nms.processors;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.EndGateway;
import org.bukkit.loot.LootTables;
import org.bukkit.entity.EnderCrystal;


public final class TheEndProcess {

    public static void process(Chunk chunk) {
        World world = chunk.getWorld();
        if(world.getEnvironment() != Environment.THE_END) return;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for(int x = 0; x < 16; x++) {
            for(int z = 0; z < 16; z++) {
                for(int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    Material type = block.getType();

                    if(type == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
                        processChestBlock(block);
                    } else if(type == Material.END_GATEWAY) {
                        processEndGatewayBlock(block);
                    }
                }
            }
        }

        processEndCrystals(chunk);
    }

    private static void processChestBlock(Block block) {
        BlockState state = block.getState(false);
        if(!(state instanceof Chest chest)) return;
        if(chest.getLootTable() != null) return;

        if(isDungeonNearby(block)) {
            chest.setLootTable(LootTables.SIMPLE_DUNGEON.getLootTable());
        } else if(isAbandonedHouse(block)) {
            chest.setLootTable(LootTables.VILLAGE_TEMPLE.getLootTable());
        } else if(isAbandonedHouse2(block) || isAbandonedHouse3(block)) {
            chest.setLootTable(LootTables.VILLAGE_PLAINS_HOUSE.getLootTable());
        } else {
            chest.setLootTable(LootTables.END_CITY_TREASURE.getLootTable());
        }

        chest.update(false, false);
    }

    private static void processEndGatewayBlock(Block block) {
        BlockState state = block.getState(false);
        if(!(state instanceof EndGateway gateway)) return;

        if(hasEndStoneBrickWalls(block)) {
            gateway.setExitLocation(block.getWorld().getBlockAt(100, 49, 0).getLocation());
            gateway.update(false, false);
        }
    }

    private static void processEndCrystals(Chunk chunk) {
        for(var entity : chunk.getEntities()) {
            if(entity instanceof EnderCrystal crystal) {
                double distance = Math.sqrt(Math.pow(crystal.getX(), 2) + Math.pow(crystal.getZ(), 2));
                if(distance > 150) {
                    crystal.setShowingBottom(false);
                }
            }
        }
    }

    private static boolean hasEndStoneBrickWalls(Block block) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        if(y + 3 >= maxY || y - 3 < minY) return false;

        return world.getBlockAt(x, y + 3, z).getType() == Material.END_STONE_BRICK_WALL
               && world.getBlockAt(x, y - 3, z).getType() == Material.END_STONE_BRICK_WALL;
    }

    private static boolean isDungeonNearby(Block center) {
        World world = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        boolean spawner = false;
        boolean ironBars = false;
        boolean stairs = false;

        for(int dx = -2; dx <= 2; dx++) {
            for(int dy = -1; dy <= 3; dy++) {
                for(int dz = -2; dz <= 2; dz++) {
                    int checkY = cy + dy;
                    if(checkY < world.getMinHeight() || checkY >= world.getMaxHeight()) {
                        continue;
                    }

                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material m = b.getType();

                    if(m == Material.SPAWNER) spawner = true;
                    else if(m == Material.IRON_BARS) ironBars = true;
                    else if(Tag.STAIRS.isTagged(m)) stairs = true;

                    if(spawner && ironBars && stairs) return true;
                }
            }
        }
        return false;
    }

    private static boolean isAbandonedHouse(Block block) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int radius = 4;
        boolean foundGlassPane = false;
        boolean foundOtherMarker = false;

        for(int dx = -radius; dx <= radius; dx++) {
            for(int dy = -radius; dy <= radius; dy++) {
                for(int dz = -radius; dz <= radius; dz++) {
                    int checkY = y + dy;
                    if(checkY < world.getMinHeight() || checkY >= world.getMaxHeight()) {
                        continue;
                    }

                    Block nearby = world.getBlockAt(x + dx, y + dy, z + dz);
                    Material mat = nearby.getType();

                    if(mat == Material.WHITE_STAINED_GLASS_PANE ||
                       mat == Material.YELLOW_STAINED_GLASS_PANE) {
                        foundGlassPane = true;
                    } else if(mat == Material.COBBLESTONE_STAIRS ||
                              mat == Material.TORCH ||
                              mat == Material.WALL_TORCH) {
                        foundOtherMarker = true;
                    }

                    if(foundGlassPane && foundOtherMarker) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isAbandonedHouse2(Block block) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int radius = 4;
        boolean foundOakStairs = false;
        boolean foundStructureBlock = false;

        for(int dx = -radius; dx <= radius; dx++) {
            for(int dy = -radius; dy <= radius; dy++) {
                for(int dz = -radius; dz <= radius; dz++) {
                    int checkY = y + dy;
                    if(checkY < world.getMinHeight() || checkY >= world.getMaxHeight()) {
                        continue;
                    }

                    Block nearby = world.getBlockAt(x + dx, y + dy, z + dz);
                    Material mat = nearby.getType();

                    if(mat == Material.OAK_STAIRS) {
                        foundOakStairs = true;
                    } else if(isStructureBlock(mat)) {
                        foundStructureBlock = true;
                    }

                    if(foundOakStairs && foundStructureBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isAbandonedHouse3(Block block) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int radius = 7;
        boolean foundFurnace = false;
        boolean foundStructureBlock = false;

        for(int dx = -radius; dx <= radius; dx++) {
            for(int dy = -radius; dy <= radius; dy++) {
                for(int dz = -radius; dz <= radius; dz++) {
                    int checkY = y + dy;
                    if(checkY < world.getMinHeight() || checkY >= world.getMaxHeight()) {
                        continue;
                    }

                    Block nearby = world.getBlockAt(x + dx, y + dy, z + dz);
                    Material mat = nearby.getType();

                    if(mat == Material.FURNACE) {
                        foundFurnace = true;
                    } else if(isStructureBlock(mat)) {
                        foundStructureBlock = true;
                    }

                    if(foundFurnace && foundStructureBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isStructureBlock(Material mat) {
        return mat == Material.OAK_LOG ||
               mat == Material.OAK_DOOR ||
               mat == Material.WALL_TORCH ||
               mat == Material.DIRT ||
               mat == Material.COBBLESTONE ||
               mat == Material.WHITE_WOOL ||
               mat == Material.WHITE_CARPET ||
               mat == Material.OAK_PLANKS ||
               mat == Material.RED_WOOL;
    }
}

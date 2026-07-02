package com.dfsek.terra.bukkit.nms.processors;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Beehive;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Chest;
import org.bukkit.entity.Bee;
import org.bukkit.loot.LootTables;

import java.util.Random;


public final class OverworldProcess {

    public static void process(Chunk chunk) {
        World world = chunk.getWorld();
        if(world.getEnvironment() != World.Environment.NORMAL) return;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for(int x = 0; x < 16; x++) {
            for(int z = 0; z < 16; z++) {
                for(int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    Material type = block.getType();

                    if(type == Material.SUSPICIOUS_GRAVEL || type == Material.SUSPICIOUS_SAND) {
                        processBrushableBlock(block, world);
                    } else if(type == Material.CHEST) {
                        processDungeonChestBlock(block);
                    } else if(type == Material.BEE_NEST) {
                        processBeeNestBlock(block);
                    }
                }
            }
        }
    }

    private static void processBrushableBlock(Block block, World world) {
        BlockState state = block.getState(false);
        if(!(state instanceof BrushableBlock brushable)) return;
        if(brushable.getLootTable() != null) return;

        Material type = block.getType();

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        int pattern = Math.abs(x * 31 + z * 17 + y * 13);

        LootTables table = null;

        if(type == Material.SUSPICIOUS_GRAVEL) {
            int v = pattern % 9;
            if(v == 0)
                table = LootTables.TRAIL_RUINS_ARCHAEOLOGY_RARE;
            else if(v < 4)
                table = LootTables.TRAIL_RUINS_ARCHAEOLOGY_COMMON;
            else if(v < 8)
                table = LootTables.OCEAN_RUIN_COLD_ARCHAEOLOGY;
        }

        if(type == Material.SUSPICIOUS_SAND) {
            int checkY = y + 1;
            if(checkY >= world.getMinHeight() && checkY < world.getMaxHeight()) {
                Block blockAbove = world.getBlockAt(x, y + 1, z);
                if(blockAbove.getType() == Material.WATER || blockAbove.getType() == Material.AIR) {
                    table = LootTables.DESERT_WELL_ARCHAEOLOGY;
                }
            }

            if(table == null) {
                switch(pattern % 3) {
                    case 0 -> table = LootTables.DESERT_PYRAMID_ARCHAEOLOGY;
                    case 1 -> table = LootTables.DESERT_WELL_ARCHAEOLOGY;
                    case 2 -> table = LootTables.OCEAN_RUIN_WARM_ARCHAEOLOGY;
                }
            }
        }

        if(table != null) {
            brushable.setLootTable(table.getLootTable());
            brushable.update(false, false);
        }
    }

    private static void processDungeonChestBlock(Block block) {
        BlockState state = block.getState(false);
        if(!(state instanceof Chest chest)) return;
        if(chest.getLootTable() != null) return;

        if(!isDungeonNearby(block)) return;

        chest.setLootTable(LootTables.SIMPLE_DUNGEON.getLootTable());
        chest.update(false, false);
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

    private static void processBeeNestBlock(Block block) {
        BlockState state = block.getState(false);
        if(!(state instanceof Beehive beehive)) return;
        if(beehive.getEntityCount() > 0) return;

        World world = block.getWorld();

        boolean attachedToTree = isAttachedToTree(block);

        int seed = block.getX() * 31 + block.getZ() * 17 + block.getY() * 13;
        Random random = new Random(seed);

        int bees;
        int honey;

        if(attachedToTree) {
            bees = 2 + random.nextInt(2);
            honey = 0;
        } else {
            bees = random.nextInt(100) < 15 ? 3 : 0;
            honey = random.nextInt(6);
        }

        Location spawnLoc = block.getLocation().add(0.5, 0.5, 0.5);

        for(int i = 0; i < bees; i++) {
            Bee bee = world.spawn(spawnLoc, Bee.class, spawned -> {
                spawned.setHive(block.getLocation());
                spawned.setHasStung(false);
                spawned.setAnger(0);
                spawned.setPersistent(false);
            });

            beehive.addEntity(bee);
            bee.remove();
        }

        beehive.update(false, false);

        org.bukkit.block.data.type.Beehive data =
            (org.bukkit.block.data.type.Beehive) block.getBlockData();
        data.setHoneyLevel(honey);
        block.setBlockData(data, false);
    }

    private static boolean isAttachedToTree(Block block) {
        for(BlockFace face : new BlockFace[]{
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
        }) {
            Material m = block.getRelative(face).getType();
            if(Tag.LOGS.isTagged(m) || Tag.LOGS_THAT_BURN.isTagged(m)) {
                return true;
            }
        }
        return false;
    }
}

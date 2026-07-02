package com.dfsek.terra.bukkit.nms.processors;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.World.Environment;


public class NetherProcess {
    public static void process(Chunk chunk) {
        World world = chunk.getWorld();
        if(world.getEnvironment() == Environment.NETHER) {
            // Nothing
        }
    }
}

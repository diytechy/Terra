package com.dfsek.terra.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.event.events.platform.PlatformInitializationEvent;
import com.dfsek.terra.cli.world.AnvilWriter;
import com.dfsek.terra.cli.world.CLIWorld;


//TODO auto pull in version
@Command(name = "TerraCLI", mixinStandardHelpOptions = true, version = "6.6.0",
         description = "Generates a Terra World and saves it in minecraft region format.")
public final class TerraCLI implements Callable<Integer> {
    @Option(names = { "-s", "--size" }, description = "Number of regions (32x32 chunks) to generate along each axis.")
    private int size = 2;

    @Option(names = "--chunks", description = "Width of the generated square in chunks. Overrides --size.")
    private int chunks = -1;

    @Option(names = "--origin-x", description = "Chunk X coordinate of the area's minimum corner (may be negative).")
    private int originX = 0;

    @Option(names = "--origin-z", description = "Chunk Z coordinate of the area's minimum corner (may be negative).")
    private int originZ = 0;

    @Option(names = { "-p", "--pack" }, description = "Config pack to use.")
    private String pack = "OVERWORLD";

    @Option(names = { "--seed" }, description = "Seed for world generation.")
    private long seed = 0;

    @Option(names = { "--max-height" }, description = "Maximum height of the world.")
    private int maxHeight = 320;

    @Option(names = { "--min-height" }, description = "Minimum height of the world.")
    private int minHeight = -64;

    @Option(names = { "--no-save" }, description = "Don't save the world to disk.")
    private boolean noSave = false;

    @Option(names = "--no-features", description = "Generate terrain only; skip the pack's population stages.")
    private boolean noFeatures = false;

    @Option(names = "--out", description = "Output directory; region files go to <out>/region.")
    private File out = new File(".");

    @Option(names = "--data-version", description = "Minecraft data version to write (required when saving; e.g. 4903 = Paper 26.2).")
    private Integer dataVersion;

    @Option(names = "--chunk-status", description = "Chunk status to write (default: minecraft:carvers without features, "
                                                    + "minecraft:features with them; never full: light, heightmaps and biomes are not written).")
    private String chunkStatus;

    @Option(names = "--threads", description = "Generation threads (default: cores - 1).")
    private int threads = Runtime.getRuntime().availableProcessors() - 1;

    public static void main(String... args) {
        int exitCode = new CommandLine(new TerraCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {
        Logger LOGGER = LoggerFactory.getLogger(TerraCLI.class);
        LOGGER.info("Starting Terra CLI...");

        if(!noSave && dataVersion == null) {
            throw new picocli.CommandLine.ParameterException(new picocli.CommandLine(this),
                "--data-version is required when saving (the target server's data version, e.g. 4903 for Paper 26.2)");
        }
        CLIPlatform platform = new CLIPlatform();
        platform.getEventManager().callEvent(new PlatformInitializationEvent());

        ConfigPack generate = platform.getConfigRegistry().getByID(pack).orElseThrow();

        CLIWorld world = new CLIWorld(seed, maxHeight, minHeight, generate, noSave, threads);
        int width = chunks > 0 ? chunks : size * 32;
        LOGGER.info("Generating {}x{} chunks from chunk ({}, {})", width, width, originX, originZ);
        world.generate(originX, originZ, width, width, !noFeatures);

        if(!noSave) {
            world.save(new File(out, "region"), dataVersion,
                chunkStatus != null ? chunkStatus : noFeatures ? AnvilWriter.STATUS_TERRAIN : AnvilWriter.STATUS_FEATURES);
        }
        LOGGER.info("Done.");
        return 0;
    }
}

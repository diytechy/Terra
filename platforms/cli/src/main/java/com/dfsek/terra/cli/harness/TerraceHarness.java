package com.dfsek.terra.cli.harness;

import com.dfsek.seismic.type.sampler.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.event.events.platform.PlatformInitializationEvent;
import com.dfsek.terra.api.properties.Properties;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.cli.CLIPlatform;
import com.dfsek.terra.cli.world.CLIWorld;


/**
 * Measurement harness for biome-border terrain (terrace blending work).
 * <ul>
 *   <li>{@code survey}: coarse surface-biome raster over a large area, to find borders cheaply;</li>
 *   <li>{@code generate}: real terrain (Anvil region files) plus a per-column surface-biome raster
 *       for a list of windows;</li>
 *   <li>{@code probe}: per-column blend diagnostics (each contributor's own height, the exact
 *       blended height) and named pack samplers.</li>
 * </ul>
 * Run from a directory containing {@code packs/} and {@code addons/}.
 */
@Command(name = "TerraceHarness", mixinStandardHelpOptions = true,
         subcommands = { TerraceHarness.Survey.class, TerraceHarness.Generate.class, TerraceHarness.Probe.class })
public final class TerraceHarness implements Callable<Integer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerraceHarness.class);

    public static void main(String... args) {
        System.exit(new CommandLine(new TerraceHarness()).execute(args));
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    abstract static class Common {
        @Option(names = { "-p", "--pack" }, description = "Config pack ID.")
        String pack = "CHIMERA";

        @Option(names = "--seed", required = true)
        long seed;

        @Option(names = "--min-height")
        int minHeight = -64;

        @Option(names = "--max-height")
        int maxHeight = 320;

        @Option(names = "--threads")
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

        ConfigPack loadPack() {
            CLIPlatform platform = new CLIPlatform();
            platform.getEventManager().callEvent(new PlatformInitializationEvent());
            return platform.getConfigRegistry().getByID(pack).orElseThrow(
                () -> new IllegalArgumentException("No pack with ID " + pack));
        }
    }

    /** Surface biome at a column, as the terrain blend sees it (pre-extrusion surface biome). */
    static Biome surfaceBiome(BiomeProvider provider, int x, int z, long seed, int min, int max) {
        return provider.getColumnForTerrain(x, z, seed, min, max).getSurface();
    }

    /** Writes a uint16 raster (x fastest) plus a JSON header with the biome ID table. */
    static void writeRaster(File dir, String name, int x0, int z0, int step, int w, int h, short[] data,
                            List<String> ids, long seed) throws IOException {
        dir.mkdirs();
        ByteBuffer buf = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for(short s : data) buf.putShort(s);
        try(OutputStream os = new FileOutputStream(new File(dir, name + ".u16"))) {
            os.write(buf.array());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seed\":").append(seed).append(",\"x0\":").append(x0).append(",\"z0\":").append(z0)
            .append(",\"step\":").append(step).append(",\"width\":").append(w).append(",\"height\":").append(h)
            .append(",\"ids\":[");
        for(int i = 0; i < ids.size(); i++) {
            if(i > 0) sb.append(',');
            sb.append('"').append(ids.get(i)).append('"');
        }
        sb.append("]}\n");
        Files.writeString(new File(dir, name + ".json").toPath(), sb.toString());
    }

    static short[] biomeRaster(BiomeProvider provider, long seed, int x0, int z0, int step, int w, int h,
                               int min, int max, List<String> ids, int threads) {
        short[] data = new short[w * h];
        Map<String, Integer> index = new ConcurrentHashMap<>();
        synchronized(ids) {
            for(int i = 0; i < ids.size(); i++) index.put(ids.get(i), i);
        }
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        AtomicInteger rows = new AtomicInteger();
        for(int j = 0; j < h; j++) {
            int jj = j;
            fs.add(ex.submit(() -> {
                for(int i = 0; i < w; i++) {
                    String id = surfaceBiome(provider, x0 + i * step, z0 + jj * step, seed, min, max).getID();
                    Integer idx = index.get(id);
                    if(idx == null) {
                        synchronized(ids) {
                            idx = index.get(id);
                            if(idx == null) {
                                idx = ids.size();
                                ids.add(id);
                                index.put(id, idx);
                            }
                        }
                    }
                    data[jj * w + i] = (short) (int) idx;
                }
                int r = rows.incrementAndGet();
                if(r % 256 == 0) LOGGER.info("Biome raster: {}/{} rows", r, h);
                return null;
            }));
        }
        try {
            for(Future<?> f : fs) f.get();
        } catch(Exception e) {
            throw new RuntimeException(e);
        } finally {
            ex.shutdown();
        }
        return data;
    }

    @Command(name = "survey", description = "Coarse surface-biome raster over a large area.")
    static final class Survey extends Common implements Callable<Integer> {
        @Option(names = "--x0", required = true) int x0;
        @Option(names = "--z0", required = true) int z0;
        @Option(names = "--size", required = true, description = "Side length in blocks.") int size;
        @Option(names = "--step", description = "Sample spacing in blocks.") int step = 8;
        @Option(names = "--out", required = true) File out;
        @Option(names = "--name") String name = "survey";

        @Override
        public Integer call() throws IOException {
            ConfigPack p = loadPack();
            int n = size / step;
            List<String> ids = new ArrayList<>();
            long t = System.nanoTime();
            short[] data = biomeRaster(p.getBiomeProvider(), seed, x0, z0, step, n, n, minHeight, maxHeight, ids, threads);
            LOGGER.info("Survey {}x{} in {}s", n, n, String.format("%.1f", (System.nanoTime() - t) / 1e9));
            writeRaster(out, name, x0, z0, step, n, n, data, ids, seed);
            return 0;
        }
    }

    @Command(name = "generate", description = "Generate terrain windows to Anvil files plus per-column biome rasters.")
    static final class Generate extends Common implements Callable<Integer> {
        @Option(names = "--windows", required = true,
                description = "Text file: one window per line: name originChunkX originChunkZ widthChunks depthChunks")
        File windows;
        @Option(names = "--out", required = true) File out;
        @Option(names = "--features", description = "Also run population stages (default: terrain only).")
        boolean features = false;
        @Option(names = "--skip-existing") boolean skipExisting = false;

        @Override
        public Integer call() throws IOException {
            ConfigPack p = loadPack();
            CLIWorld world = new CLIWorld(seed, maxHeight, minHeight, p, false, threads);
            for(String line : Files.readAllLines(windows.toPath())) {
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                String name = t[0];
                int ox = Integer.parseInt(t[1]), oz = Integer.parseInt(t[2]);
                int w = Integer.parseInt(t[3]), d = Integer.parseInt(t[4]);
                File dir = new File(out, name);
                if(skipExisting && new File(dir, "biomes.json").exists()) {
                    LOGGER.info("Skipping existing window {}", name);
                    continue;
                }
                LOGGER.info("Window {}: chunks ({}, {}) size {}x{}", name, ox, oz, w, d);
                world.generate(ox, oz, w, d, features);
                world.save(new File(dir, "region"));
                world.clear();
                List<String> ids = new ArrayList<>();
                short[] data = biomeRaster(p.getBiomeProvider(), seed, ox * 16, oz * 16, 1, w * 16, d * 16,
                    minHeight, maxHeight, ids, threads);
                writeRaster(dir, "biomes", ox * 16, oz * 16, 1, w * 16, d * 16, data, ids, seed);
            }
            return 0;
        }
    }

    @Command(name = "probe", description = "Per-column blend diagnostics and pack sampler values.")
    static final class Probe extends Common implements Callable<Integer> {
        @Option(names = "--points", required = true,
                description = "Text file: 'x z [tag [BIOME_A,BIOME_B...]]' per line; listed biomes get their own height reported.")
        File points;
        @Option(names = "--out", required = true) File out;
        @Option(names = "--samplers", split = ",",
                description = "Named 2D pack samplers to evaluate at each column.")
        List<String> samplerNames = new ArrayList<>(List.of(
            "biomeInfluence", "normDistIntoBiome", "elevationDetailedBlockHeight"));

        @Override
        public Integer call() throws Exception {
            ConfigPack p = loadPack();
            Object generator = p.getGeneratorProvider().newInstance(p);
            Class<?> probeClass = generator.getClass().getClassLoader()
                .loadClass("com.dfsek.terra.addons.chunkgenerator.debug.TerrainProbe");
            Method probe = probeClass.getMethod("probe", ConfigPack.class, long.class, int.class, int.class, int.class, int.class,
                String[].class);

            Map<String, Sampler> named = packSamplers(p, samplerNames);

            List<String[]> pts = new ArrayList<>();
            for(String line : Files.readAllLines(points.toPath())) {
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;
                pts.add(line.split("\\s+"));
            }
            String[] results = new String[pts.size()];
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            List<Future<?>> fs = new ArrayList<>();
            AtomicInteger done = new AtomicInteger();
            for(int i = 0; i < pts.size(); i++) {
                int ii = i;
                fs.add(ex.submit(() -> {
                    String[] t = pts.get(ii);
                    int x = Integer.parseInt(t[0]), z = Integer.parseInt(t[1]);
                    String[] own = t.length > 3 ? t[3].split(",") : new String[0];
                    String json = (String) probe.invoke(null, p, seed, x, z, minHeight, maxHeight, (Object) own);
                    StringBuilder sb = new StringBuilder(json.substring(0, json.length() - 1));
                    if(t.length > 2) sb.append(",\"tag\":\"").append(t[2]).append('"');
                    sb.append(",\"samplers\":{");
                    boolean first = true;
                    for(Map.Entry<String, Sampler> e : named.entrySet()) {
                        if(!first) sb.append(',');
                        first = false;
                        double v = e.getValue().getSample(seed, x, z);
                        sb.append('"').append(e.getKey()).append("\":")
                            .append(Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "null");
                    }
                    sb.append("}}");
                    results[ii] = sb.toString();
                    int n = done.incrementAndGet();
                    if(n % 200 == 0) LOGGER.info("Probed {}/{}", n, pts.size());
                    return null;
                }));
            }
            try {
                for(Future<?> f : fs) f.get();
            } finally {
                ex.shutdown();
            }
            out.getAbsoluteFile().getParentFile().mkdirs();
            try(BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
                for(String r : results) {
                    w.write(r);
                    w.write('\n');
                }
            }
            LOGGER.info("Wrote {} probes to {}", results.length, out);
            return 0;
        }

        /** Look up named pack samplers through the noise addon's pack template, reflectively. */
        static Map<String, Sampler> packSamplers(ConfigPack p, List<String> names) throws Exception {
            Map<String, Sampler> out = new LinkedHashMap<>();
            Properties template = p.getContext().getByClassName("com.dfsek.terra.addons.noise.NoiseConfigPackTemplate");
            if(template == null) {
                LOGGER.warn("Noise addon pack template not found; no named samplers");
                return out;
            }
            Map<?, ?> samplers = (Map<?, ?>) template.getClass().getMethod("getSamplers").invoke(template);
            for(String name : names) {
                Object das = samplers.get(name);
                if(das == null) {
                    LOGGER.warn("Pack sampler {} not found", name);
                    continue;
                }
                out.put(name, (Sampler) das.getClass().getMethod("getSampler").invoke(das));
            }
            return out;
        }
    }
}

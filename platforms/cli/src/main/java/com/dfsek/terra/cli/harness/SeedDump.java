package com.dfsek.terra.cli.harness;

import com.dfsek.seismic.type.sampler.Sampler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.dfsek.terra.api.config.ConfigPack;


/**
 * Several seeds, one pack, one JVM: evaluates the same columns for each seed (in the given order, or
 * all seeds concurrently) and writes one dump per seed with the surface biome, the exact blended
 * terrain height and named pack samplers. Diff a seed's dump against a run that evaluated only that
 * seed to detect state shared between seeds (e.g. caches not keyed on the seed).
 */
@Command(name = "seed-dump", description = "Dump biomes/heights/samplers for several seeds in one JVM.")
final class SeedDump extends TerraceHarness.Common implements Callable<Integer> {
    @Option(names = "--seeds", required = true, split = ",", description = "Seeds, evaluated in this order.")
    List<Long> seeds;

    @Option(names = "--points", required = true, description = "Columns, one 'x z' per line.")
    File points;

    @Option(names = "--out-prefix", required = true, description = "Writes <prefix><seed>.txt per seed.")
    String outPrefix;

    @Option(names = "--concurrent", description = "Evaluate all seeds at the same time, one thread per seed.")
    boolean concurrent = false;

    @Option(names = "--samplers", split = ",", description = "Named 2D pack samplers to dump.")
    List<String> samplerNames = new ArrayList<>(List.of("continentalRiverDist", "riverSampler"));

    @Override
    public Integer call() throws Exception {
        ConfigPack p = loadPack();
        Object generator = p.getGeneratorProvider().newInstance(p);
        Method probe = generator.getClass().getClassLoader()
            .loadClass("com.dfsek.terra.addons.chunkgenerator.debug.TerrainProbe")
            .getMethod("probe", ConfigPack.class, long.class, int.class, int.class, int.class, int.class);
        Map<String, Sampler> named = TerraceHarness.Probe.packSamplers(p, samplerNames);
        List<int[]> cols = new ArrayList<>();
        for(String line : Files.readAllLines(points.toPath())) {
            line = line.trim();
            if(line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            cols.add(new int[]{ Integer.parseInt(t[0]), Integer.parseInt(t[1]) });
        }
        ExecutorService ex = Executors.newFixedThreadPool(concurrent ? seeds.size() : 1);
        List<Future<?>> fs = new ArrayList<>();
        for(long s : seeds) {
            fs.add(ex.submit(() -> {
                StringBuilder sb = new StringBuilder();
                for(int[] c : cols) {
                    String biome = TerraceHarness.surfaceBiome(p.getBiomeProvider(), c[0], c[1], s, minHeight, maxHeight).getID();
                    String json = (String) probe.invoke(null, p, s, c[0], c[1], minHeight, maxHeight);
                    int i = json.indexOf("\"blendedTop\":") + 13, j = i;
                    while(j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
                    sb.append(c[0]).append(' ').append(c[1]).append(' ').append(biome).append(' ').append(json, i, j);
                    for(Sampler smp : named.values()) {
                        sb.append(' ').append(String.format(Locale.ROOT, "%.6f", smp.getSample(s, c[0], c[1])));
                    }
                    sb.append(System.lineSeparator());
                }
                Files.writeString(new File(outPrefix + s + ".txt").toPath(), sb.toString());
                return null;
            }));
            if(!concurrent) fs.get(fs.size() - 1).get();
        }
        for(Future<?> f : fs) f.get();
        ex.shutdown();
        return 0;
    }
}

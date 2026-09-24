package com.dfsek.terra.cli.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;


/**
 * Order-independence check: evaluates the same columns under several evaluation orders in one JVM
 * and reports every column whose surface biome or terrain height differs between orders.
 * <p>
 * Every trial starts from empty provider caches (all Caffeine caches reachable from the pack's biome
 * provider are invalidated reflectively) on fresh threads, so a trial sees only its own history
 * (thread-local sampler state lives on the trial's own threads). Trials:
 * reference (file order, one thread), repeat (sanity), primed ({@code --prime} columns first),
 * shuffled, reversed, and parallel (round-robin over {@code --threads} threads).
 * Exit code 1 if any trial differs from the reference.
 */
@Command(name = "order-check", description = "Detect evaluation-order dependence of biomes and terrain heights.")
final class OrderCheck extends TerraceHarness.Common implements Callable<Integer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderCheck.class);

    @Option(names = "--points", required = true, description = "Target columns, one 'x z' per line.")
    File points;

    @Option(names = "--prime", description = "Columns evaluated first in the primed trial, one 'x z' per line.")
    File prime;

    @Option(names = "--no-terrain", description = "Only compare biomes (skip the terrain probe).")
    boolean noTerrain = false;

    @Option(names = "--dump", description = "Write the reference trial's results ('x z biome height') here.")
    File dump;

    @Override
    public Integer call() throws Exception {
        ConfigPack p = loadPack();
        BiomeProvider provider = p.getBiomeProvider();
        Object generator = p.getGeneratorProvider().newInstance(p);
        Method probe = generator.getClass().getClassLoader()
            .loadClass("com.dfsek.terra.addons.chunkgenerator.debug.TerrainProbe")
            .getMethod("probe", ConfigPack.class, long.class, int.class, int.class, int.class, int.class);

        List<int[]> targets = read(points);
        List<int[]> primeCols = prime != null ? read(prime) : List.of();

        String[] ref = trial("reference", p, provider, probe, List.of(targets), List.of(), targets);
        if(dump != null) {
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < ref.length; i++) sb.append(targets.get(i)[0]).append(' ').append(targets.get(i)[1]).append(' ')
                .append(ref[i]).append(System.lineSeparator());
            Files.writeString(dump.toPath(), sb.toString());
        }
        String[] names = { "repeat", "primed", "shuffled", "reversed", "parallel" };
        String[][] others = {
            trial(names[0], p, provider, probe, List.of(targets), List.of(), targets),
            trial(names[1], p, provider, probe, List.of(targets), primeCols, targets),
            trial(names[2], p, provider, probe, List.of(shuffled(targets, 42)), List.of(), targets),
            trial(names[3], p, provider, probe, List.of(reversed(targets)), List.of(), targets),
            trial(names[4], p, provider, probe, split(shuffled(targets, 7), threads), List.of(), targets),
        };
        int bad = 0;
        for(int t = 0; t < others.length; t++) {
            int diffBiome = 0, diffHeight = 0;
            String example = null;
            for(int i = 0; i < ref.length; i++) {
                if(ref[i].equals(others[t][i])) continue;
                String[] a = ref[i].split(" "), b = others[t][i].split(" ");
                if(!a[0].equals(b[0])) diffBiome++;
                if(a.length > 1 && b.length > 1 && !a[1].equals(b[1])) diffHeight++;
                if(example == null) example = targets.get(i)[0] + "," + targets.get(i)[1] + ": " + ref[i] + " vs " + others[t][i];
            }
            if(diffBiome + diffHeight > 0) bad++;
            System.out.printf("ORDER-CHECK %-9s columns=%d biomeDiffs=%d heightDiffs=%d%s%n", names[t], ref.length, diffBiome,
                diffHeight, example != null ? "  e.g. " + example : "");
        }
        System.out.println(bad == 0 ? "ORDER-CHECK PASS" : "ORDER-CHECK FAIL (" + bad + " trials differ)");
        return bad == 0 ? 0 : 1;
    }

    /** One trial on fresh threads after clearing provider caches; results in target-file order. */
    private String[] trial(String name, ConfigPack p, BiomeProvider provider, Method probe, List<List<int[]>> perThread,
                           List<int[]> primeCols, List<int[]> order) throws Exception {
        int n = invalidateCaches(provider);
        Map<Long, String> out = new ConcurrentHashMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(perThread.size());
        List<Future<?>> fs = new ArrayList<>();
        for(List<int[]> cols : perThread) {
            fs.add(ex.submit(() -> {
                for(int[] c : primeCols) eval(p, provider, probe, c[0], c[1]);
                for(int[] c : cols) out.put(key(c), eval(p, provider, probe, c[0], c[1]));
                return null;
            }));
        }
        try {
            for(Future<?> f : fs) f.get();
        } finally {
            ex.shutdown();
        }
        String[] res = new String[order.size()];
        for(int i = 0; i < res.length; i++) res[i] = out.get(key(order.get(i)));
        LOGGER.info("trial {} done ({} columns, {} caches invalidated)", name, res.length, n);
        return res;
    }

    private String eval(ConfigPack p, BiomeProvider provider, Method probe, int x, int z) throws Exception {
        String biome = TerraceHarness.surfaceBiome(provider, x, z, seed, minHeight, maxHeight).getID();
        if(noTerrain) return biome;
        String json = (String) probe.invoke(null, p, seed, x, z, minHeight, maxHeight);
        int i = json.indexOf("\"blendedTop\":") + 13;
        int j = i;
        while(j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        return biome + " " + json.substring(i, j);
    }

    private static long key(int[] c) {
        return (((long) c[0]) << 32) ^ (c[1] & 0xFFFFFFFFL);
    }

    private static List<int[]> read(File f) throws Exception {
        List<int[]> l = new ArrayList<>();
        for(String line : Files.readAllLines(f.toPath())) {
            line = line.trim();
            if(line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            l.add(new int[]{ Integer.parseInt(t[0]), Integer.parseInt(t[1]) });
        }
        return l;
    }

    private static List<int[]> shuffled(List<int[]> l, long s) {
        List<int[]> c = new ArrayList<>(l);
        Collections.shuffle(c, new Random(s));
        return c;
    }

    private static List<int[]> reversed(List<int[]> l) {
        List<int[]> c = new ArrayList<>(l);
        Collections.reverse(c);
        return c;
    }

    private static List<List<int[]>> split(List<int[]> l, int n) {
        List<List<int[]>> out = new ArrayList<>();
        for(int i = 0; i < n; i++) out.add(new ArrayList<>());
        for(int i = 0; i < l.size(); i++) out.get(i % n).add(l.get(i));
        return out;
    }

    /** Invalidate every Caffeine cache reachable (depth <= 6) from root through com.dfsek objects. */
    static int invalidateCaches(Object root) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return walk(root, 0, seen);
    }

    private static int walk(Object o, int depth, Set<Object> seen) {
        if(o == null || depth > 6 || !seen.add(o)) return 0;
        Class<?> c = o.getClass();
        if(c.getName().contains("caffeine")) {
            try {
                Method m = c.getMethod("invalidateAll");
                m.setAccessible(true);
                m.invoke(o);
                return 1;
            } catch(Exception e) {
                return 0;
            }
        }
        if(!c.getName().startsWith("com.dfsek")) return 0;
        int n = 0;
        for(Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for(Field f : k.getDeclaredFields()) {
                if(Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    n += walk(f.get(o), depth + 1, seen);
                } catch(Exception ignored) {
                    // inaccessible field: skip
                }
            }
        }
        return n;
    }
}

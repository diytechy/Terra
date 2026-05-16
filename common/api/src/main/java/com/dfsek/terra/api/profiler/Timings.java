/*
 * Copyright (c) 2020-2025 Polyhedral Development
 *
 * The Terra API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the common/api directory.
 */

package com.dfsek.terra.api.profiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class Timings {
    private final Map<String, Timings> subItems = new HashMap<>();

    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private long sum = 0;
    private int count = 0;

    public void addTime(long time) {
        if(time < min) min = time;
        if(time > max) max = time;
        sum += time;
        count++;
    }

    public void merge(long min, long max, long sum, int count) {
        if(count > 0) {
            if(min < this.min) this.min = min;
            if(max > this.max) this.max = max;
            this.sum += sum;
            this.count += count;
        }
    }

    public double average() {
        return count == 0 ? 0 : (double) sum / count;
    }

    public long max() {
        return count == 0 ? 0 : max;
    }

    public long min() {
        return count == 0 ? 0 : min;
    }

    public double sum() {
        return (double) sum;
    }

    public int count() {
        return count;
    }

    /** Render this entry with percentage relative to an externally-supplied total (for root-level entries). */
    public String toString(double totalNanos) {
        return toString(1, totalNanos, Collections.emptySet());
    }

    @Override
    public String toString() {
        return toString(1, sum(), Collections.emptySet());
    }

    private String toString(int indent, double parentSum, Set<Integer> branches) {
        StringBuilder builder = new StringBuilder();

        double percent = parentSum > 0 ? (sum() / parentSum) * 100 : 0;
        builder.append(String.format("%6.2f%%", percent))
            .append("  ")
            .append(String.format("%.2f", (double) min() / 1000000)).append("ms min / ")
            .append(String.format("%.2f", average() / 1000000)).append("ms avg / ")
            .append(String.format("%.2f", (double) max() / 1000000)).append("ms max (")
            .append(count).append(" samples)");

        List<Map.Entry<String, Timings>> entries = new ArrayList<>(subItems.entrySet());
        for(int i = 0; i < entries.size(); i++) {
            boolean isLast = (i == entries.size() - 1);
            // Last child does not continue the parent branch, so omit current indent from its descendants
            Set<Integer> childBranches = new HashSet<>(branches);
            if(!isLast) childBranches.add(indent);

            String frame = entries.get(i).getKey() + ": " +
                entries.get(i).getValue().toString(indent + 1, sum(), childBranches);

            builder.append('\n');
            for(int j = 0; j < indent; j++) {
                builder.append(branches.contains(j) ? "│   " : "    ");
            }
            builder.append(isLast ? "└───" : "├───");
            builder.append(frame);
        }
        return builder.toString();
    }

    public Map<String, Timings> getSubItems() {
        return Collections.unmodifiableMap(subItems);
    }

    public Timings getSubItem(String id) {
        return subItems.computeIfAbsent(id, s -> new Timings());
    }
}

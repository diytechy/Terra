/*
 * This file is part of Terra.
 *
 * Terra is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Terra is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Terra.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.dfsek.terra.profiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.dfsek.terra.api.profiler.Profiler;
import com.dfsek.terra.api.profiler.Timings;
import com.dfsek.terra.api.util.mutable.MutableInteger;
import com.dfsek.terra.profiler.exception.MalformedStackException;


public class ProfilerImpl implements Profiler {
    private static final Logger logger = LoggerFactory.getLogger(ProfilerImpl.class);

    private static final ThreadLocal<Stack<Frame>> THREAD_STACK = ThreadLocal.withInitial(Stack::new);
    // long[4]: {min, max, sum, count} per frame key
    private static final ThreadLocal<Map<String, long[]>> TIMINGS = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Boolean> SAFE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<MutableInteger> STACK_SIZE = ThreadLocal.withInitial(() -> new MutableInteger(0));
    private static boolean instantiated = false;
    private final List<Map<String, long[]>> accessibleThreadMaps = new ArrayList<>();
    private volatile boolean running = false;

    public ProfilerImpl() {
        if(instantiated)
            throw new IllegalStateException("Only one instance of Profiler may exist!");
        instantiated = true;
    }

    @Override
    public void push(String frame) {
        if(running) {
            STACK_SIZE.get().increment();
            if(SAFE.get()) {
                Stack<Frame> stack = THREAD_STACK.get();
                stack.push(new Frame(stack.isEmpty() ? frame : stack.peek().getId() + "." + frame));
            } else SAFE.set(false);
        }
    }

    @Override
    public void pop(String frame) {
        if(running) {
            MutableInteger size = STACK_SIZE.get();
            size.decrement();
            if(SAFE.get()) {
                Stack<Frame> stack = THREAD_STACK.get();
                if(stack.isEmpty()) {
                    // Profiler was started mid-chunk: the matching push ran before the profiler
                    // was active so it never reached THREAD_STACK. Reset so the next complete
                    // chunk is tracked correctly.
                    SAFE.set(false);
                    return;
                }
                long time = System.nanoTime();

                Map<String, long[]> timingsMap = TIMINGS.get();

                if(timingsMap.isEmpty()) {
                    synchronized(accessibleThreadMaps) {
                        accessibleThreadMaps.add(timingsMap);
                    }
                }

                Frame top = stack.pop();
                if(!stack.isEmpty() ? !top.getId().endsWith("." + frame) : !top.getId().equals(frame))
                    throw new MalformedStackException("Expected " + frame + ", found " + top);

                long elapsed = time - top.getStart();
                long[] s = timingsMap.computeIfAbsent(top.getId(),
                    id -> new long[]{ Long.MAX_VALUE, Long.MIN_VALUE, 0L, 0L });
                if(elapsed < s[0]) s[0] = elapsed;
                if(elapsed > s[1]) s[1] = elapsed;
                s[2] += elapsed;
                s[3]++;
            }
            if(size.get() == 0) SAFE.set(true);
        }
    }

    @Override
    public void start() {
        logger.info("Starting Terra profiler");
        running = true;
    }

    @Override
    public void stop() {
        logger.info("Stopping Terra profiler");
        running = false;
        SAFE.set(false);
    }

    @Override
    public void reset() {
        logger.info("Resetting Terra profiler");
        synchronized(accessibleThreadMaps) {
            accessibleThreadMaps.forEach(Map::clear);
            accessibleThreadMaps.clear();
        }
    }

    @Override
    public Map<String, Timings> getTimings() {
        Map<String, Timings> map = new HashMap<>();
        List<Map<String, long[]>> snapshot;
        synchronized(accessibleThreadMaps) {
            snapshot = new ArrayList<>(accessibleThreadMaps);
        }
        for(Map<String, long[]> smap : snapshot) {
            Map<String, long[]> smapCopy;
            try {
                smapCopy = new HashMap<>(smap);
            } catch(ConcurrentModificationException e) {
                continue; // Thread is actively modifying its map, skip it this cycle
            }
            for(Map.Entry<String, long[]> entry : smapCopy.entrySet()) {
                String[] keys = entry.getKey().split("\\.");
                Timings timings = map.computeIfAbsent(keys[0], id -> new Timings());
                for(int i = 1; i < keys.length; i++) {
                    timings = timings.getSubItem(keys[i]);
                }
                long[] s = entry.getValue();
                timings.merge(s[0], s[1], s[2], (int) s[3]);
            }
        }
        return map;
    }
}

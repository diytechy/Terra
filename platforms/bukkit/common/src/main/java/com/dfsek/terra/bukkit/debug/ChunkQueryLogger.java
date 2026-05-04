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

package com.dfsek.terra.bukkit.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Debug logger that records every chunk generation request to a CSV file.
 * Enabled via {@code debug.queries: true} in Terra's config.yml.
 * <p>
 * Logging is off the hot path: generateNoise() enqueues a pre-formatted string
 * into a lock-free queue; a background daemon thread drains it to disk.
 * <p>
 * Output: {@code terra_chunk_queries_YYYY-MM-DD_HH-mm-ss.csv} in the server's
 * working directory (same folder as server.jar).
 * Columns: {@code timestamp_ms,thread,world,pack,chunk_x,chunk_z}
 */
public final class ChunkQueryLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkQueryLogger.class);
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String CSV_HEADER = "timestamp_ms,thread,world,pack,chunk_x,chunk_z";

    // Enough headroom for a burst of 64k pending records before dropping.
    private static final int QUEUE_CAPACITY = 65536;

    // Volatile so generateNoise() threads always see the latest value without synchronization.
    private static volatile ChunkQueryLogger INSTANCE = null;

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writerThread;
    private final BufferedWriter writer;

    private ChunkQueryLogger(Path filePath) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(CSV_HEADER);
        writer.newLine();
        writer.flush();

        this.writerThread = new Thread(this::drainLoop, "terra-chunk-query-logger");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Open the log file and start the background writer thread.
     * No-op if already initialised.
     */
    public static void init() {
        if(INSTANCE != null) return;
        String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
        Path filePath = Paths.get("terra_chunk_queries_" + timestamp + ".csv");
        try {
            INSTANCE = new ChunkQueryLogger(filePath);
            LOGGER.info("Chunk query logging active. File: {}", filePath.toAbsolutePath());
        } catch(IOException e) {
            LOGGER.error("Failed to open chunk query log file {}", filePath.toAbsolutePath(), e);
        }
    }

    /**
     * Record a single chunk generation request.
     * Called from generateNoise() on generation worker threads; must be non-blocking.
     * Drops silently if the logger is not initialised or the queue is full.
     */
    public static void log(String world, String pack, int chunkX, int chunkZ) {
        ChunkQueryLogger instance = INSTANCE;
        if(instance == null) return;
        // Pre-format here on the caller thread to keep the writer thread as simple as possible.
        // Thread.currentThread().getName() identifies Paper vs Chunky generation threads.
        String record = System.currentTimeMillis()
            + "," + Thread.currentThread().getName()
            + "," + world
            + "," + pack
            + "," + chunkX
            + "," + chunkZ;
        if(!instance.queue.offer(record)) {
            LOGGER.warn("Chunk query log queue full — record dropped");
        }
    }

    /**
     * Flush remaining records and close the file. Called on server shutdown.
     */
    public static void close() {
        ChunkQueryLogger instance = INSTANCE;
        if(instance == null) return;
        INSTANCE = null;
        instance.running.set(false);
        instance.writerThread.interrupt();
        try {
            instance.writerThread.join(5_000);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Drain anything left in the queue after the writer thread exits.
        try {
            String record;
            while((record = instance.queue.poll()) != null) {
                instance.writer.write(record);
                instance.writer.newLine();
            }
            instance.writer.flush();
            instance.writer.close();
            LOGGER.info("Chunk query log closed.");
        } catch(IOException e) {
            LOGGER.error("Failed to close chunk query log", e);
        }
    }

    private void drainLoop() {
        try {
            while(running.get() || !queue.isEmpty()) {
                // Block up to 100 ms waiting for the first record of a batch.
                String record = queue.poll(100, TimeUnit.MILLISECONDS);
                if(record == null) continue;

                writer.write(record);
                writer.newLine();

                // Drain additional queued records in the same flush cycle.
                int batchCount = 0;
                while(batchCount < 512) {
                    String next = queue.poll();
                    if(next == null) break;
                    writer.write(next);
                    writer.newLine();
                    batchCount++;
                }
                writer.flush();
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(IOException e) {
            LOGGER.error("Chunk query log write error", e);
        }
    }
}

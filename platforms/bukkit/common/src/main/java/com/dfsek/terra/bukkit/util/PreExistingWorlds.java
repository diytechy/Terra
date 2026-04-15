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

package com.dfsek.terra.bukkit.util;

import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


/**
 * Snapshot of world folders that existed on disk when Terra was enabled.
 * Populated once at plugin onEnable, before any worlds are initialized
 * (Terra loads at STARTUP). Used to distinguish new-world creation from
 * existing-world loading in WorldInitEvent handlers.
 */
public final class PreExistingWorlds {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreExistingWorlds.class);
    private static Set<String> snapshot = Collections.emptySet();
    private static boolean snapshotTaken = false;

    private PreExistingWorlds() {}

    /**
     * Scan the server's world container and record the names of all folders
     * that contain a {@code level.dat} file. Must be called before any world
     * is initialized.
     */
    public static void snapshot() {
        Set<String> found = new HashSet<>();
        try {
            File container = Bukkit.getWorldContainer();
            File[] entries = container.listFiles();
            if(entries != null) {
                for(File entry : entries) {
                    if(entry.isDirectory() && new File(entry, "level.dat").isFile()) {
                        found.add(entry.getName());
                    }
                }
            }
        } catch(Exception e) {
            LOGGER.debug("Failed to scan world container for pre-existing worlds", e);
        }
        snapshot = Collections.unmodifiableSet(found);
        snapshotTaken = true;
        LOGGER.debug("Recorded {} pre-existing world folder(s): {}", snapshot.size(), snapshot);
    }

    /**
     * @return true if the given world name was not present on disk when the snapshot was taken.
     *         Returns false if no snapshot was taken (fails safe — no warning).
     */
    public static boolean isNewWorld(String worldName) {
        return snapshotTaken && !snapshot.contains(worldName);
    }
}

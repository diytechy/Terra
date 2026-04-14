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

import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;


public final class SpigotConfigUtil {
    public static final int RECOMMENDED_TIMEOUT = 1800;
    private static final Logger LOGGER = LoggerFactory.getLogger(SpigotConfigUtil.class);

    private SpigotConfigUtil() {}

    public static int getTimeoutTime() {
        try {
            File spigotYml = new File("spigot.yml");
            if(!spigotYml.exists()) {
                return -1;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(spigotYml);
            return config.getInt("settings.timeout-time", 60);
        } catch(Exception e) {
            LOGGER.debug("Could not read timeout-time from spigot.yml", e);
            return -1;
        }
    }

    public static boolean isTimeoutTooLow() {
        int timeout = getTimeoutTime();
        return timeout > 0 && timeout < RECOMMENDED_TIMEOUT;
    }
}

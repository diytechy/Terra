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

package com.dfsek.terra.bukkit.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dfsek.terra.bukkit.util.SpigotConfigUtil;


public class ServerLoadListener implements Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerLoadListener.class);
    private static final String BANNER =
        "================================================================================";

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if(SpigotConfigUtil.isTimeoutTooLow()) {
            int current = SpigotConfigUtil.getTimeoutTime();
            int recommended = SpigotConfigUtil.RECOMMENDED_TIMEOUT;
            LOGGER.warn("""

                        {}
                                                      TERRA WARNING
                        {}
                         Your spigot.yml timeout-time is set to {} seconds.
                         It is recommended to set timeout-time to at least {} seconds (30 minutes)
                         in spigot.yml to prevent the server watchdog from killing the server
                         during initial world creation with Terra.

                         You may ignore this warning if you do not intend to generate any new
                         worlds with Terra in this session.
                        {}
                        """.strip(), BANNER, BANNER, current, recommended, BANNER);
        }
    }
}

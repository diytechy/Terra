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

package com.dfsek.terra.fabric;

import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandSourceStack;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.fabric.FabricServerCommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dfsek.terra.api.command.CommandSender;
import com.dfsek.terra.api.event.events.platform.CommandRegistrationEvent;


public class FabricEntryPoint implements ModInitializer {
    private static final Logger logger = LoggerFactory.getLogger(FabricEntryPoint.class);
    private static final FabricPlatform TERRA_PLUGIN = new FabricPlatform();

    @Override
    public void onInitialize() {
        // Command registration lives here (not in the shared lifecycle module) because it is bound to
        // the loader-specific cloud command manager — Fabric uses cloud-fabric, NeoForge cloud-neoforge.
        logger.info("Initializing Terra Fabric mod...");

        FabricServerCommandManager<CommandSender> manager = new FabricServerCommandManager<>(
            ExecutionCoordinator.asyncCoordinator(),
            SenderMapper.create(
                serverCommandSource -> (CommandSender) serverCommandSource,
                commandSender -> (CommandSourceStack) commandSender)
        );

        manager.brigadierManager().setNativeNumberSuggestions(false);

        TERRA_PLUGIN.getEventManager().callEvent(new CommandRegistrationEvent(manager));
    }
}

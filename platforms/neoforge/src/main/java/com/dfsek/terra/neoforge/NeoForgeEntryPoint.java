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

package com.dfsek.terra.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.neoforge.NeoForgeServerCommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dfsek.terra.api.command.CommandSender;
import com.dfsek.terra.api.event.events.platform.CommandRegistrationEvent;


/**
 * NeoForge entrypoint. The heavy lifting (biome / codec / world-preset registration) is handled by the
 * shared mixin-common + mixin-lifecycle mixins against the vanilla MC registry load — identical to
 * Fabric — so this only stands up the platform and wires the cloud-neoforge command manager.
 */
@Mod("terra")
public class NeoForgeEntryPoint {
    private static final Logger logger = LoggerFactory.getLogger(NeoForgeEntryPoint.class);
    private static final NeoForgePlatform TERRA_PLUGIN = new NeoForgePlatform();

    public NeoForgeEntryPoint(IEventBus modBus, ModContainer container) {
        logger.info("Initializing Terra NeoForge mod...");

        NeoForgeServerCommandManager<CommandSender> manager = new NeoForgeServerCommandManager<>(
            ExecutionCoordinator.asyncCoordinator(),
            SenderMapper.create(
                serverCommandSource -> (CommandSender) serverCommandSource,
                commandSender -> (CommandSourceStack) commandSender)
        );

        manager.brigadierManager().setNativeNumberSuggestions(false);

        TERRA_PLUGIN.getEventManager().callEvent(new CommandRegistrationEvent(manager));
    }
}

/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.locateplus.core.LPConfig;
import net.minecraft.commands.CommandSourceStack;

/** Single registration entry point for every command. */
public final class LPCommands {

    private LPCommands() {
    }

    public static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher,
                                   net.minecraft.commands.CommandBuildContext registryAccess) {
        LPConfig config = LPConfig.get();

        // Always registered: /lp is how an operator discovers everything else, including which
        // commands the config has switched off.
        HelpCommand.register(dispatcher);

        if (config.commandSafeTp()) {
            SafeTpCommand.register(dispatcher);
        }
        if (config.commandGlow()) {
            GlowCommand.register(dispatcher);
        }

        if (config.commandLocateBlock() || config.commandLocateEntity() || config.commandLocateItem()) {
            LocateCommand.register(dispatcher);
        }

        // Registered last so its /locate biome and /locate structure replace vanilla's.
        if (config.commandVanillaLocateOverride()) {
            VanillaLocateCommand.register(dispatcher, registryAccess);
        }
    }
}

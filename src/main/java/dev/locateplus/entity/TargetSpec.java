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
package dev.locateplus.entity;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;

/** What the player typed after {@code /locate entity}, resolved but not yet evaluated. */
public interface TargetSpec {

    /** Human readable form of the target, echoed back in chat and written into exports. */
    String label();

    /**
     * Turn this spec into a concrete predicate against the world as it exists right now.
     *
     * @throws CommandSyntaxException only for input that cannot be interpreted at all, never
     *                                merely because nothing matched
     */
    EntityQuery bind(CommandSourceStack source) throws CommandSyntaxException;

    /**
     * True when only players can match. Lets the scanner walk the (always loaded) player list
     * instead of the entity lookup, which is both faster and immune to chunk-loading races.
     */
    default boolean playersOnly() {
        return false;
    }
}

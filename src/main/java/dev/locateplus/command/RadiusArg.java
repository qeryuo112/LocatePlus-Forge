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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.locateplus.util.Radius;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Builds the shared {@code <number> chunks|blocks [forceload]} tail.
 *
 * Every scanning command uses this, so they cannot drift apart in what they accept.
 */
public final class RadiusArg {

    private static final DynamicCommandExceptionType TOO_SMALL = new DynamicCommandExceptionType(
            value -> Component.literal("Radius " + value + " is too small; use 1 or more."));

    /**
     * Raised when a chunk radius exceeds what the region can be built for.
     *
     * A radius describes a disc, so the chunk count is its square: the ceiling is about holding the
     * list of positions in memory, not about how long a scan would take.
     */
    private static final DynamicCommandExceptionType TOO_LARGE = new DynamicCommandExceptionType(
            detail -> Component.literal("Radius too large. " + detail + "."));

    /** What a command does once its radius and forceload flag are known. */
    @FunctionalInterface
    public interface Action {

        int run(CommandContext<CommandSourceStack> ctx, Radius radius, boolean forceload)
                throws CommandSyntaxException;
    }

    /** The radius number, shared by every command that takes one. */
    public static RequiredArgumentBuilder<CommandSourceStack, Integer> number() {
        return argument("radius", IntegerArgumentType.integer(0, Radius.hardMaxBlocks()));
    }

    private RadiusArg() {
    }

    /**
     * Attach the radius tail to {@code parent}.
     *
     * @param supportsForceload whether a trailing {@code forceload} literal should be offered
     */
    public static <T extends ArgumentBuilder<CommandSourceStack, T>> T attach(
            T parent, boolean supportsForceload, Action action) {
        // The Brigadier range is the widest the config could ever allow, because the command tree
        // is built once at startup and cannot be rebuilt by /lp reload.
        RequiredArgumentBuilder<CommandSourceStack, Integer> number = number();

        addUnit(number, "chunks", false, supportsForceload, action);
        addUnit(number, "blocks", true, supportsForceload, action);

        return parent.then(number);
    }

    private static void addUnit(RequiredArgumentBuilder<CommandSourceStack, Integer> number,
                                String word, boolean isBlocks, boolean supportsForceload,
                                Action action) {
        var unit = literal(word)
                .executes(ctx -> action.run(ctx, read(ctx, isBlocks), false));

        if (supportsForceload) {
            unit.then(literal("forceload")
                    .executes(ctx -> action.run(ctx, read(ctx, isBlocks), true)));
        }
        number.then(unit);
    }

    /** Read the typed number for the chosen unit. */
    public static Radius read(CommandContext<CommandSourceStack> ctx, boolean isBlocks)
            throws CommandSyntaxException {
        return read(IntegerArgumentType.getInteger(ctx, "radius"), isBlocks);
    }

    /** Apply the unit and the configured ceilings to an already-read number. */
    public static Radius read(int value, boolean isBlocks) throws CommandSyntaxException {
        if (isBlocks) {
            if (value < 1) {
                throw TOO_SMALL.create(value);
            }
            if (value > Radius.maxBlocks()) {
                throw TOO_LARGE.create(value + " blocks is over the limit of "
                        + Radius.maxBlocks() + "; raise max_block_radius in the config to allow "
                        + "more");
            }
            return Radius.ofBlocks(value);
        }
        if (value > Radius.maxChunks()) {
            throw TOO_LARGE.create(value + " chunks reaches " + (value * 16)
                    + " blocks; the most is " + Radius.maxChunks()
                    + ". Raise max_chunk_radius in the config to allow more");
        }
        return Radius.ofChunks(value);
    }
}

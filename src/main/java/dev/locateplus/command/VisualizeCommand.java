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
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.Highlights;
import dev.locateplus.util.Radius;
import dev.locateplus.visual.RegionOutline;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** {@code /lp visualize <n> chunks|blocks [seconds]}, an outline of the area a scan would cover. */
public final class VisualizeCommand {

    private static final int DEFAULT_SECONDS = 60;
    private static final int MAX_SECONDS = 3_600;

    private VisualizeCommand() {
    }

    /**
     * The {@code clear} subtree, attached to {@code /lp} alongside {@code visualize}.
     *
     * Named for what it does to the outline rather than to the view. Nothing is being concealed and
     * there is no matching "show", so "hide" invited the question of how to get it back.
     */
    static LiteralArgumentBuilder<CommandSourceStack> buildClear() {
        return literal("clear")
                .executes(ctx -> clear(ctx, true, true, true))
                .then(literal("all").executes(ctx -> clear(ctx, true, true, true)))
                .then(literal("both").executes(ctx -> clear(ctx, true, true, true)))
                .then(literal("glow").executes(ctx -> clear(ctx, false, true, false)))
                .then(literal("markers").executes(ctx -> clear(ctx, false, false, true)))
                // Kept as a hidden alias: this was called "particles" while the block marker was
                // made of them, and anything scripted against the old name still works.
                .then(literal("particles").executes(ctx -> clear(ctx, false, false, true)));
    }

    /** The {@code visualize} subtree, attached to {@code /lp} by {@link HelpCommand}. */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        // No .executes() on the root: an amount and a unit are always required, so a bare /lp
        // visualize is a usage error rather than a guess at what was meant.
        LiteralArgumentBuilder<CommandSourceStack> root = literal("visualize");

        RequiredArgumentBuilder<CommandSourceStack, Integer> number =
                RadiusArg.number();

        for (String word : new String[]{"chunks", "blocks"}) {
            boolean isBlocks = word.equals("blocks");

            number.then(literal(word)
                    .executes(ctx -> show(ctx, RadiusArg.read(ctx, isBlocks), DEFAULT_SECONDS))
                    .then(argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                            .executes(ctx -> show(ctx, RadiusArg.read(ctx, isBlocks),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))));
        }
        return root.then(number);
    }

    /**
     * Clear what this mod is showing, all of it or one kind.
     *
     * Only what this mod put there is touched. Glow is cleared from the entities it lit, by
     * identity, so an effect from a beacon, a spectral arrow or another mod is left alone.
     */
    private static int clear(CommandContext<CommandSourceStack> ctx, boolean outline,
                             boolean glow, boolean markers) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();

        boolean clearedOutline = outline && player != null && RegionOutline.hide(player);
        int unglowed = glow ? Highlights.clearGlow(source.getLevel()) : 0;
        int beacons = markers ? Highlights.clearBeacons(source.getLevel()) : 0;

        List<String> done = new ArrayList<>(3);
        if (clearedOutline) {
            done.add("the outline");
        }
        if (unglowed > 0) {
            done.add("the glow on " + Msg.count(unglowed, "entity", "entities"));
        }
        if (beacons > 0) {
            done.add(Msg.count(beacons, "block marker"));
        }

        if (done.isEmpty()) {
            Msg.note(source, "Nothing was showing.");
            return 1;
        }
        Msg.success(source, "Cleared " + join(done) + ".");
        return 1;
    }

    /** {@code "a"}, {@code "a and b"}, {@code "a, b and c"}. */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String last = parts.get(parts.size() - 1);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + last;
    }

    private static int show(CommandContext<CommandSourceStack> ctx, Radius radius, int seconds)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Msg.error(source, "Only a player can see an outline. "
                    + "Run this in game rather than from the console.");
            return 0;
        }

        ServerLevel world = source.getLevel();
        Vec3 anchor = source.getPosition();
        BlockPos anchorBlock = BlockPos.containing(anchor);
        boolean byBlocks = radius.typedUnit() == Radius.Unit.BLOCKS;

        // Worked out from the radius rather than by building the chunk list. This command draws
        // an edge, and an edge grows with the radius while the area it encloses grows with its
        // square, so a large request costs no more than a small one.
        ChunkPos originChunk = new ChunkPos(anchorBlock);
        int chunkRadius = byBlocks ? ((radius.blocks() + 15) >> 4) + 1 : radius.chunks();

        long total = RegionOutline.discChunkCount(chunkRadius);
        long loaded = RegionOutline.countLoadedInDisc(world, originChunk, chunkRadius);
        long unloaded = total - loaded;

        RegionOutline outline = byBlocks
                ? RegionOutline.showCircle(player, world, anchor, radius.blocks(), seconds)
                : RegionOutline.showChunkRadius(player, world, anchor, originChunk, chunkRadius,
                        seconds);

        Msg.heading(source, "Scan area outline");
        Msg.field(source, "Requested", Msg.area(radius));
        Msg.field(source, "Covers", Msg.count(total, "chunk"));
        Msg.field(source, "Boundary", byBlocks
                ? "a circle " + radius.blocks() + " blocks out"
                : "the edge of the chunks picked, about " + ((chunkRadius + 1) * 16)
                        + " blocks out");
        Msg.field(source, "Loaded now", Msg.number(loaded) + " of " + Msg.number(total),
                unloaded == 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE);
        if (unloaded > 0) {
            // This command scans nothing, so nothing is being skipped here.
            Msg.field(source, "Not loaded", Msg.number(unloaded)
                    + ", red on the outline", ChatFormatting.RED);
            Msg.note(source, "A scan here would miss those unless you add 'forceload'.");
        }
        Msg.field(source, "Showing for", Msg.count(seconds, "second"));

        Msg.blank(source);
        Msg.note(source, "Pale line is loaded, red is not loaded, blue marks where you ran it.");

        int hidden = outline.outOfRangeCount();
        if (hidden > 0) {
            Msg.note(source, Msg.number(hidden) + " of " + Msg.number(outline.postCount())
                    + " markers " + (hidden == 1 ? "is" : "are") + " past the "
                    + RegionOutline.drawRange()
                    + " blocks a client will draw. Walk towards them and they appear.");
            if (seconds < 120) {
                Msg.note(source, "Add a longer time to walk out there, for example: "
                        + "/lp visualize " + rawValue(radius) + " "
                        + (byBlocks ? "blocks" : "chunks") + " 600");
            }
        }
        Msg.note(source, "Run /lp clear to remove it.");
        return 1;
    }


    private static int rawValue(Radius radius) {
        return radius.typedUnit() == Radius.Unit.CHUNKS ? radius.chunks() : radius.blocks();
    }
}

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
package dev.locateplus.report;

import dev.locateplus.core.LPConfig;
import dev.locateplus.model.BlockTally;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.teleport.TeleportService;
import dev.locateplus.util.Radius;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Turns a {@link ScanResult} into the chat report a player reads. */
public final class ChatReporter {

    private ChatReporter() {
    }

    // ---- /lp analyze blocks -------------------------------------------------------------------

    public static void blockAnalysis(CommandSourceStack source, ScanResult result, boolean exported) {
        blockAnalysis(source, result, exported, false);
    }

    /**
     *
     * @param combined true when this is one half of {@code /lp analyze both}, which prints the
     *                 area once above both halves and one footer below them
     */
    public static void blockAnalysis(CommandSourceStack source, ScanResult result,
                                     boolean exported, boolean combined) {
        if (combined) {
            Msg.blank(source);
            Msg.section(source, "Blocks: " + Msg.number(result.positionsScanned()) + " checked");
            if (result.blocksByCount().isEmpty()) {
                Msg.note(source, "Nothing but air.");
                return;
            }
        } else {
            Msg.heading(source, "Block analysis");
            scanScope(source, result);
            Msg.field(source, "Blocks checked", Msg.number(result.positionsScanned()));
        }

        List<BlockTally> tallies = result.blocksByCount();
        if (tallies.isEmpty()) {
            Msg.note(source, "No blocks found.");
            return;
        }

        long total = result.totalMatches();
        int shown = Math.min(LPConfig.get().chatTopN(), tallies.size());
        if (!combined) {
            Msg.blank(source);
        }

        for (int i = 0; i < shown; i++) {
            BlockTally tally = tallies.get(i);
            double share = total == 0 ? -1 : (double) tally.count() / total;
            int rank = i + 1;

            BlockPos nearest = tally.nearest();
            source.sendSuccess(() -> Msg.resultLine(rank, tally.id().toString(),
                    tally.count(), share, null,
                    nearest == null ? null : TeleportService.teleportButton(nearest),
                    nearest == null ? null : Msg.coords(nearest)), false);
        }

        if (tallies.size() > shown) {
            Msg.more(source, tallies.size() - shown, "block types",
                    "showing the " + shown + " biggest; add 'export' for the whole list");
        }
        if (!combined) {
            footer(source, result, exported, "blocks");
        }
    }

    // ---- /lp analyze entities -----------------------------------------------------------------

    public static void entityAnalysis(CommandSourceStack source, ScanResult result, boolean exported) {
        entityAnalysis(source, result, exported, false);
    }

    /**
     *
     * @param combined as in {@link #blockAnalysis}
     */
    public static void entityAnalysis(CommandSourceStack source, ScanResult result,
                                      boolean exported, boolean combined) {
        List<ScanResult.TypeCount> counts = result.entitiesByCount();
        long total = result.entities().size();

        if (combined) {
            Msg.blank(source);
            Msg.section(source, counts.isEmpty()
                    ? "Entities: none found"
                    : "Entities: " + Msg.number(total) + " found");
            if (counts.isEmpty()) {
                return;
            }
        } else {
            Msg.heading(source, "Entity analysis");
            scanScope(source, result);
            if (counts.isEmpty()) {
                Msg.note(source, "No entities found.");
                return;
            }
        }

        if (!combined) {
            Msg.field(source, "Entities found", Msg.number(total));
            Msg.blank(source);
        }

        int shown = Math.min(LPConfig.get().chatTopN(), counts.size());

        for (int i = 0; i < shown; i++) {
            ScanResult.TypeCount entry = counts.get(i);
            double share = total == 0 ? -1 : (double) entry.count() / total;
            int rank = i + 1;

            EntityRecord nearest = entry.nearest();
            source.sendSuccess(() -> Msg.resultLine(rank, entry.id().toString(),
                    entry.count(), share, null,
                    nearest == null ? null
                            : TeleportService.teleportButton(nearest.blockPos()),
                    nearest == null ? null : Msg.coords(nearest.blockPos())), false);
        }

        if (counts.size() > shown) {
            Msg.more(source, counts.size() - shown, "entity types",
                    "showing the " + shown + " most common; add 'export' for the whole list");
        }
        if (!combined) {
            footer(source, result, exported, "entities");
        }
    }

    /** The area line that opens {@code /lp analyze both}, covering both halves. */
    public static void analysisScope(CommandSourceStack source, ScanResult result) {
        scanScope(source, result);
    }

    /** The one footer that closes {@code /lp analyze both}. */
    public static void analysisFooter(CommandSourceStack source, ScanResult result,
                                      boolean exported, long millis) {
        footer(source, result, exported, "both", millis);
    }


    // ---- /locate block ------------------------------------------------------------------------

    /** Result of {@code /locate block}. */
    public static void locateBlock(CommandSourceStack source, ScanResult result,
                                   String label, Radius radius) {
        List<BlockTally> tallies = result.blocksByCount();
        if (tallies.isEmpty()) {
            Msg.warn(source, "No " + label + " within " + describe(radius) + ".");
            suggestForceload(source, result);
            return;
        }

        Msg.heading(source, "Nearest " + label);
        Msg.field(source, "Found", Msg.count(result.totalMatches(), "match", "matches")
                + " within " + describe(radius));

        if (tallies.size() == 1) {
            singleBlockResult(source, tallies.get(0));
        } else {
            multipleBlockResults(source, tallies);
        }
        // Only promise an outline when one was actually drawn.
        if (LPConfig.get().markLocatedBlocks()) {
            Msg.note(source, LPConfig.get().blockMarkerNeverExpires()
                    ? "The nearest one is glowing through the ground until you mine it."
                    : "The nearest one is glowing through the ground for "
                            + Msg.count(LPConfig.get().blockMarkerTicks() / 20, "second") + ".");
        }
    }

    /** One type: a single line naming it, where it is, and how far. */
    private static void singleBlockResult(CommandSourceStack source, BlockTally tally) {
        BlockPos pos = tally.nearest();
        if (pos == null) {
            return;
        }
        String id = tally.id().toString();

        source.sendSuccess(() -> Component.literal("  ")
                .append(Component.literal(id).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" at " + Msg.coords(pos) + "  ").withStyle(ChatFormatting.GRAY))
                .append(TeleportService.teleportButton(pos)), false);
    }

    /**
     * Several types, as happens with a tag: a per-type breakdown, closest type first.
     *
     * Ordered by distance rather than count, because "what is nearest" is the question {@code
     * /locate} is asked. The count still appears on each line.
     */
    private static void multipleBlockResults(CommandSourceStack source, List<BlockTally> tallies) {
        List<BlockTally> byDistance = new ArrayList<>(tallies);
        byDistance.sort(Comparator.comparingDouble(BlockTally::nearestDistance));

        Msg.blank(source);
        int shown = Math.min(LPConfig.get().chatTopN(), byDistance.size());

        for (int i = 0; i < shown; i++) {
            BlockTally tally = byDistance.get(i);
            BlockPos pos = tally.nearest();
            if (pos == null) {
                continue;
            }
            int rank = i + 1;

            source.sendSuccess(() -> Msg.resultLine(rank, tally.id().toString(),
                    tally.count(), -1, null,
                    TeleportService.teleportButton(pos), Msg.coords(pos)), false);
        }

        if (byDistance.size() > shown) {
            Msg.more(source, byDistance.size() - shown, "block types");
        }
    }

    // ---- /locate entity -----------------------------------------------------------------------

    public static void locateEntity(CommandSourceStack source, ScanResult result,
                                    String label, Radius radius) {
        EntityRecord nearest = result.nearestEntity();
        if (nearest == null) {
            Msg.warn(source, "No " + label + " within " + describe(radius) + ".");
            suggestForceload(source, result);
            return;
        }

        Msg.heading(source, "Nearest " + label);
        Msg.field(source, "Found", Msg.count(result.entities().size(), "match", "matches")
                + " within " + describe(radius));

        MutableComponent line = Component.literal("  ")
                .append(Component.literal(nearest.typeId().toString()).withStyle(ChatFormatting.WHITE));
        if (nearest.customName() != null) {
            line.append(Component.literal(" \"" + nearest.customName() + "\"")
                    .withStyle(ChatFormatting.YELLOW));
        }
        line.append(Component.literal(" at " + Msg.coords(nearest.blockPos()) + "  ")
                        .withStyle(ChatFormatting.GRAY))
                .append(TeleportService.teleportButton(nearest.blockPos()));
        source.sendSuccess(() -> line, false);

        if (nearest.health() != null) {
            Msg.field(source, "Health", String.format("%.1f / %.1f",
                    nearest.health(), nearest.maxHealth()));
        }
        if (nearest.itemId() != null) {
            Msg.field(source, "Item", nearest.itemCount() + "x " + nearest.itemId());
        }
        if (LPConfig.get().glowLocatedEntities()) {
            Msg.note(source, "It is glowing for a minute. Everyone nearby can see that.");
        }
    }

    // ---- shared pieces ------------------------------------------------------------------------

    /** The radius as the player expressed it, so the echo matches what they typed. */
    private static String describe(Radius radius) {
        return Msg.area(radius);
    }

    /** The one or two lines describing what a scan covered. */
    private static void scanScope(CommandSourceStack source, ScanResult result) {
        String scanned = Msg.count(result.chunksScanned(), "chunk");
        int requested = result.requestedChunks();
        if (requested > 0 && result.chunksScanned() != requested) {
            scanned += " of " + Msg.number(requested) + " requested";
        }
        Msg.field(source, "Scanned", scanned);
        if (result.chunksSkipped() > 0) {
            Msg.field(source, "Skipped", Msg.count(result.chunksSkipped(), "unloaded chunk"),
                    ChatFormatting.YELLOW);
        }
    }

    /**
     * When a search comes up empty without force-loading, the usual reason is simply that the
     * chunks were not in memory. Say so, rather than letting it look like the mod is broken.
     */
    private static void suggestForceload(CommandSourceStack source, ScanResult result) {
        if (!result.forceload() && result.chunksSkipped() > 0) {
            Msg.note(source, Msg.count(result.chunksSkipped(), "chunk")
                    + " in range were not loaded. Add 'forceload' to include them.");
        }
    }

    private static void footer(CommandSourceStack source, ScanResult result,
                               boolean exported, String kind) {
        footer(source, result, exported, kind, result.durationMillis());
    }

    private static void footer(CommandSourceStack source, ScanResult result,
                               boolean exported, String kind, long millis) {
        if (result.truncated()) {
            Msg.blank(source);
            Msg.warn(source, "Result set was very large; some coordinates were left out "
                    + "of the export.");
        }
        Msg.blank(source);
        // Nothing is said about the export here. It is still being written at this point, and
        // the writer names the file itself once it is actually on disk.
        if (!exported) {
            // Echo the radius exactly as it was typed. Without one the scan covered whatever was
            // loaded, and there is no radius to put back, so the click primes the command and
            // leaves the player to say how far.
            String radius = result.requestedRadius();
            boolean known = !radius.isEmpty();
            String start = "/lp analyze " + kind + " ";
            String command = known ? start + radius + " export" : start;
            Msg.clickToRun(source, "Click here to export the full list to a file", command,
                    known
                            ? "Puts this in your chat box, ready to send:\n" + command
                            : "Puts this in your chat box:\n" + command
                                    + "\nAdd a radius such as 4 chunks, then export.");
        }
        Msg.note(source, "Took " + Msg.duration(millis) + ".");
    }
}

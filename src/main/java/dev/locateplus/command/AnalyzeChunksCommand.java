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

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.entity.TargetSpec;
import dev.locateplus.model.ScanResult;
import dev.locateplus.report.Chat;
import dev.locateplus.report.ChatReporter;
import dev.locateplus.report.ExportWriter;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.BlockMatcher;
import dev.locateplus.scan.BlockScanJob;
import dev.locateplus.scan.EntityScanJob;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** {@code /lp analyze blocks|entities|both [radius] [export] [forceload]}. */
public final class AnalyzeChunksCommand {

    private AnalyzeChunksCommand() {
    }

    /** The {@code analyze} subtree, attached to {@code /lp} by {@link HelpCommand}. */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("analyze")
                .then(mode("blocks", AnalyzeChunksCommand::blocks))
                .then(mode("entities", AnalyzeChunksCommand::entities))
                .then(mode("both", AnalyzeChunksCommand::both));
    }

    /**
     * Builds one mode's subtree: {@code <n> chunks|blocks [export] [forceload]}.
     *
     * {@link RadiusArg#read} does the range validation, so an oversized radius is handled here
     * exactly as it is in every other command.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> mode(String name, Runner runner) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal(name)
                .executes(ctx -> runner.run(ctx, Radius.ofLoaded(), false, false));

        RequiredArgumentBuilder<CommandSourceStack, Integer> number =
                RadiusArg.number();

        for (String word : new String[]{"chunks", "blocks"}) {
            boolean isBlocks = word.equals("blocks");
            number.then(literal(word)
                    .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), false, false))

                    // ...
                    .then(literal("forceload")
                            .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), false, true))
                            .then(literal("export")
                                    .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), true, true))))

                    // ...
                    .then(literal("export")
                            .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), true, false))
                            .then(literal("forceload")
                                    .executes(ctx -> runner.run(ctx, RadiusArg.read(ctx, isBlocks), true, true)))));
        }
        return root.then(number);
    }

    @FunctionalInterface
    private interface Runner {
        int run(CommandContext<CommandSourceStack> ctx, Radius radius, boolean export,
                boolean forceload) throws CommandSyntaxException;
    }

    // ---- modes --------------------------------------------------------------------------------

    private static int blocks(CommandContext<CommandSourceStack> ctx, Radius radius,
                              boolean export, boolean forceload) {
        return runBlocks(ctx.getSource(), radius, export, forceload, null);
    }

    private static int entities(CommandContext<CommandSourceStack> ctx, Radius radius,
                                boolean export, boolean forceload) {
        return runEntities(ctx.getSource(), radius, export, forceload, null);
    }

    /**
     * Both scans, chained.
     *
     * The two halves cover the same area and are asked for in one breath, so they read as one
     * report: the area is stated once above them, and the closing advice and timing once below.
     * The timing is the two scans added together, which is the wait the player actually had.
     */
    private static int both(CommandContext<CommandSourceStack> ctx, Radius radius,
                            boolean export, boolean forceload) {
        CommandSourceStack source = ctx.getSource();
        Combined combined = new Combined();
        return runBlocks(source, radius, export, forceload,
                () -> runEntities(source, radius, export, forceload, null, combined), combined);
    }

    /**
     * State shared by the two halves of a combined run.
     *
     * One instance per command, so two players analyzing at the same time cannot read each
     * other's timings.
     */
    private static final class Combined {
        long blockMillis;
        /** The blocks half, held back so both files are written and announced together. */
        ScanResult blocks;
    }

    // ---- implementations ----------------------------------------------------------------------

    private static int runBlocks(CommandSourceStack source, Radius radius, boolean export,
                                 boolean forceload, Runnable then) {
        return runBlocks(source, radius, export, forceload, then, null);
    }

    private static int runBlocks(CommandSourceStack source, Radius radius, boolean export,
                                 boolean forceload, Runnable then, Combined shared) {
        boolean combined = shared != null;
        ServerLevel world = source.getLevel();
        Vec3 origin = source.getPosition();
        BlockPos originBlock = BlockPos.containing(origin);

        ScanRegion region = chunkRegion(originBlock, radius, forceload, world);
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        ScanResult result = new ScanResult(ScanResult.Kind.BLOCKS,
                world.dimension().location().toString(), originBlock, origin,
                radius.chunks(), 0, forceload);

        // The radius number is a reach, so comparing it against chunks scanned would read as "5 of
        // 5" for an area of eighty-one.
        result.setRequestedChunks(region.totalChunks());
        result.setRequestedRadius(typed(radius));
        Msg.info(source, "Analyzing " + (combined ? "blocks and entities" : "blocks") + " in "
                + Msg.area(radius) + " (" + Msg.count(region.totalChunks(), "chunk") + ")...");

        LPScheduler.submit(new BlockScanJob(world, region, BlockMatcher.allNonAir(), result,
                export, 0, origin, false,
                done -> {
                    if (combined) {
                        Msg.heading(source, "Analysis");
                        ChatReporter.analysisScope(source, done);
                        shared.blockMillis = done.durationMillis();
                        shared.blocks = done;
                    }
                    ChatReporter.blockAnalysis(source, done, export, combined);
                    // In a combined run the entities half is still to print, so the export is
                    // held back rather than cutting the report in two.
                    if (export && !combined) {
                        exportAsync(source, done);
                    }
                    if (forceload && !combined) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                    if (then != null) {
                        then.run();
                    }
                },
                error -> Chat.error(source, "Block analysis failed: " + error.getMessage())));
        return 1;
    }

    private static int runEntities(CommandSourceStack source, Radius radius, boolean export,
                                   boolean forceload, Runnable then) {
        return runEntities(source, radius, export, forceload, then, null);
    }

    private static int runEntities(CommandSourceStack source, Radius radius, boolean export,
                                   boolean forceload, Runnable then, Combined shared) {
        boolean combined = shared != null;
        ServerLevel world = source.getLevel();
        Vec3 origin = source.getPosition();
        BlockPos originBlock = BlockPos.containing(origin);

        ScanRegion region = chunkRegion(originBlock, radius, forceload, world);
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        ScanResult result = new ScanResult(ScanResult.Kind.ENTITIES,
                world.dimension().location().toString(), originBlock, origin,
                radius.chunks(), 0, forceload);

        // The radius number is a reach, so comparing it against chunks scanned would read as "5 of
        // 5" for an area of eighty-one.
        result.setRequestedChunks(region.totalChunks());
        result.setRequestedRadius(typed(radius));
        if (!combined) {
            Msg.info(source, "Analyzing entities in " + Msg.area(radius) + " ("
                    + Msg.count(region.totalChunks(), "chunk") + ")...");
        }

        TargetSpec everything = new TargetSpec() {
            @Override
            public String label() {
                return "all entities";
            }

            @Override
            public EntityQuery bind(CommandSourceStack src) {
                return EntityQuery.all();
            }
        };

        LPScheduler.submit(new EntityScanJob(world, source, region, everything, result, 0, origin,
                done -> {
                    ChatReporter.entityAnalysis(source, done, export, combined);
                    if (combined) {
                        ChatReporter.analysisFooter(source, done, export,
                                shared.blockMillis + done.durationMillis());
                        if (export) {
                            exportBoth(source, shared.blocks, done);
                        }
                    } else if (export) {
                        exportAsync(source, done);
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                    if (then != null) {
                        then.run();
                    }
                },
                error -> Chat.error(source, "Entity analysis failed: " + error.getMessage())));
        return 1;
    }

    /**
     * Both units describe a reach, so both produce a disc; only the number differs.
     *
     * Kept as one call rather than branching on the unit, so this command cannot drift from what
     * {@link ScanRegion#forRadius} builds for every other command.
     */
    private static ScanRegion chunkRegion(BlockPos origin, Radius radius, boolean forceload,
                                          ServerLevel world) {
        return ScanRegion.forRadius(origin, radius, forceload, world);
    }

    /** The radius as typed, for echoing back in advice: {@code "4 chunks"}, {@code "64 blocks"}. */
    private static String typed(Radius radius) {
        return radius.typedUnit() == Radius.Unit.CHUNKS
                ? radius.chunks() + " chunks"
                : radius.blocks() + " blocks";
    }


    /**
     * Write both export files on the background pool.
     *
     * Chat feedback is safe from another thread because {@code sendFeedback} only enqueues a
     * packet.
     */
    private static void exportAsync(CommandSourceStack source, ScanResult result) {
        LPScheduler.background().execute(() -> ExportWriter.writeAsyncLogged(result,
                paths -> {
                    Msg.note(source, "Export written: " + paths[0].getFileName());
                    Msg.note(source, "in " + Msg.exportPath());
                },
                error -> Msg.error(source, "Export failed: " + error.getMessage())));
    }

    /**
     * Write both halves of a combined run and name them in one place.
     *
     * Two separate writes would each announce themselves, and the first would land between the
     * Blocks list and the Entities list because the blocks half finishes first.
     */
    private static void exportBoth(CommandSourceStack source, ScanResult blocks,
                                   ScanResult entities) {
        LPScheduler.background().execute(() -> {
            try {
                java.nio.file.Path blockFile = ExportWriter.write(blocks)[0];
                java.nio.file.Path entityFile = ExportWriter.write(entities)[0];
                Msg.note(source, "Exports written: " + blockFile.getFileName()
                        + " and " + entityFile.getFileName());
                Msg.note(source, "in " + Msg.exportPath());
            } catch (Throwable t) {
                LPLog.error("Export failed", t);
                Msg.error(source, "Export failed: " + t.getMessage());
            }
        });
    }
}

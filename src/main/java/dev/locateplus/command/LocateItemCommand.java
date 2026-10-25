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
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.model.ItemResult;
import dev.locateplus.report.Chat;
import dev.locateplus.report.ItemExportWriter;
import dev.locateplus.report.ItemReporter;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.ItemMatcher;
import dev.locateplus.scan.ItemScanJob;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /locate item <item_id|#tag> [<n> chunks|blocks] [export] [forceload]}.
 *
 * Like the rest of the mod this uses only vanilla argument types, so the command tree still
 * serialises correctly to a vanilla client at login.
 */
public final class LocateItemCommand {

    private static final DynamicCommandExceptionType UNKNOWN_ITEM = new DynamicCommandExceptionType(
            id -> Component.literal("No item or item tag matching '" + id + "'"));

    private LocateItemCommand() {
    }

    /** The {@code item} subtree, ready to attach to {@code /locate}. */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> item = literal("item");

        RequiredArgumentBuilder<CommandSourceStack,
                ResourceOrTagKeyArgument.Result<Item>> target =
                argument("item", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.ITEM))
                        .executes(ctx -> run(ctx, Radius.ofLoaded(), false, false));

        RequiredArgumentBuilder<CommandSourceStack, Integer> number =
                RadiusArg.number();

        // export and forceload may be given in either order, matching /lp analyze.
        for (String word : new String[]{"chunks", "blocks"}) {
            boolean isBlocks = word.equals("blocks");
            number.then(literal(word)
                    .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), false, false))

                    .then(literal("forceload")
                            .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), false, true))
                            .then(literal("export")
                                    .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), true, true))))

                    .then(literal("export")
                            .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), true, false))
                            .then(literal("forceload")
                                    .executes(ctx -> run(ctx, RadiusArg.read(ctx, isBlocks), true, true)))));
        }

        return item.then(target.then(number));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Radius radiusSpec,
                           boolean export, boolean forceload) throws CommandSyntaxException {
        return scan(ctx, radiusSpec, export, forceload, toItemMatcher(
                ResourceOrTagKeyArgument.getResourceOrTagKey(ctx, "item", Registries.ITEM,
                        UNKNOWN_ITEM)));
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's item matcher. */
    private static ItemMatcher toItemMatcher(
            ResourceOrTagKeyArgument.Result<Item> predicate)
            throws CommandSyntaxException {
        com.mojang.datafixers.util.Either<net.minecraft.resources.ResourceKey<Item>, TagKey<Item>>
                key = predicate.unwrap();
        boolean missing = key.map(
                k -> !BuiltInRegistries.ITEM.containsKey(k),
                tag -> BuiltInRegistries.ITEM.getTag(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_ITEM.create(key.map(k -> k.location().toString(),
                    tag -> "#" + tag.location()));
        }
        return key.map(
                itemKey -> ItemMatcher.ofItem(itemKey.location().toString(),
                        BuiltInRegistries.ITEM.get(itemKey)),
                ItemMatcher::ofTag);
    }

    private static int scan(CommandContext<CommandSourceStack> ctx, Radius radiusSpec,
                            boolean export, boolean forceload, ItemMatcher matcher)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        Vec3 origin = source.getPosition();
        BlockPos originBlock = BlockPos.containing(origin);

        ScanRegion region = ScanRegion.forRadius(originBlock, radiusSpec, forceload, world);
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        int radius = radiusSpec.typedUnit() == Radius.Unit.BLOCKS ? radiusSpec.blocks() : 0;

        ItemResult result = new ItemResult(matcher.label(),
                world.dimension().location().toString(), originBlock, origin,
                region.chunkRadius(), radius, forceload);

        // The radius number is a reach, so comparing it against chunks scanned would read as "5 of
        // 5" for an area of eighty-one.
        result.setRequestedChunks(region.totalChunks());
        result.setRequestedRadius(radiusSpec.isLoadedArea() ? "" : typedRadius(radiusSpec));

        Msg.info(source, "Searching for " + matcher.label() + " within "
                + Msg.area(radiusSpec) + " (" + Msg.count(region.totalChunks(), "chunk")
                + ")...");

        LPScheduler.submit(new ItemScanJob(world, region, matcher, result, radius, origin,
                done -> {
                    ItemReporter.report(source, done, radiusSpec, export);
                    if (export) {
                        exportAsync(source, done);
                    }
                    if (forceload) {
                        Msg.note(source, "Temporary forced chunks released.");
                    }
                },
                error -> Chat.error(source, "Item search failed: " + error.getMessage())));
        return 1;
    }

    /** Bridge a vanilla registry predicate (id or tag) onto this mod's item matcher. */


    private static void exportAsync(CommandSourceStack source, ItemResult result) {
        LPScheduler.background().execute(() -> ItemExportWriter.writeAsyncLogged(result,
                path -> {
                    Msg.note(source, "Export written: " + path.getFileName());
                    Msg.note(source, "in " + Msg.exportPath());
                },
                error -> Msg.error(source, "Export failed: " + error.getMessage())));
    }

    /** {@code "4 chunks"} or {@code "64 blocks"}, whichever the player asked for. */
    private static String typedRadius(Radius radius) {
        return radius.typedUnit() == Radius.Unit.CHUNKS
                ? radius.chunks() + " chunks"
                : radius.blocks() + " blocks";
    }
}

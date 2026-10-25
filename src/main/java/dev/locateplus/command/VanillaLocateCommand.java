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
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPLog;
import dev.locateplus.report.Chat;
import dev.locateplus.report.Msg;
import dev.locateplus.teleport.TeleportService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Replaces vanilla {@code /locate biome} and {@code /locate structure} so their results carry a
 * safe-teleport button instead of a raw {@code /tp} link.
 *
 * The argument types must match vanilla's <em>exactly</em>. In 1.20.1 the two subcommands do not
 * agree with each other, so each is registered against the type vanilla actually uses.
 */
public final class VanillaLocateCommand {

    /** Vanilla's search budget for /locate biome. */
    private static final int BIOME_SEARCH_RADIUS = 6400;
    private static final int BIOME_SEARCH_STEP = 32;
    private static final int BIOME_BLOCK_CHECK_INTERVAL = 64;

    /** Vanilla's search budget for /locate structure, in chunks. */
    private static final int STRUCTURE_SEARCH_CHUNKS = 100;

    private static final DynamicCommandExceptionType BIOME_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Component.literal("Could not find a biome of type \"" + id + "\" within "
                    + BIOME_SEARCH_RADIUS + " blocks"));

    private static final DynamicCommandExceptionType STRUCTURE_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Component.literal("Could not find a structure of type \"" + id + "\" nearby"));

    /** Blocks searched outward from the caller for a point of interest. */
    private static final int POI_SEARCH_RADIUS = 512;

    /** Chunk rings loaded when the in-memory index comes up empty. */
    private static final int POI_SEARCH_CHUNK_RINGS = 12;

    private static final DynamicCommandExceptionType POI_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Component.literal("Could not find a point of interest of type \"" + id
                    + "\" within " + POI_SEARCH_RADIUS + " blocks"));

    private static final DynamicCommandExceptionType STRUCTURE_INVALID = new DynamicCommandExceptionType(
            id -> Component.literal("There is no structure with type \"" + id + "\" in this world"));

    private VanillaLocateCommand() {
    }

    /**
     * Register the overriding {@code /locate biome} and {@code /locate structure}.
     *
     * Registered as its own {@code /locate} root rather than being attached to this mod's builder,
     * so the merge happens against vanilla's existing tree.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext access) {
        dispatcher.register(literal("locate")
                .requires(source -> source.hasPermission(LPConfig.get().permissionLevel()))

                .then(literal("biome")
                        .then(argument("biome",
                                ResourceOrTagArgument.resourceOrTag(access, Registries.BIOME))
                                .executes(VanillaLocateCommand::locateBiome)))

                .then(literal("structure")
                        .then(argument("structure",
                                ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .executes(VanillaLocateCommand::locateStructure)))

                .then(literal("poi")
                        .then(argument("poi", ResourceOrTagArgument
                                .resourceOrTag(access, Registries.POINT_OF_INTEREST_TYPE))
                                .executes(VanillaLocateCommand::locatePoi))));
    }

    // ---- biome --------------------------------------------------------------------------------

    private static int locateBiome(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        ResourceOrTagArgument.Result<Biome> predicate =
                ResourceOrTagArgument.getResourceOrTag(
                        ctx, "biome", Registries.BIOME);

        Chat.info(source, "Searching for biome " + predicate.asPrintable() + "...");

        Pair<BlockPos, Holder<Biome>> found = world.findClosestBiome3d(
                predicate, origin, BIOME_SEARCH_RADIUS, BIOME_SEARCH_STEP, BIOME_BLOCK_CHECK_INTERVAL);

        if (found == null) {
            throw BIOME_NOT_FOUND.create(predicate.asPrintable());
        }

        BlockPos target = found.getFirst();
        String name = found.getSecond().unwrapKey()
                .map(key -> key.location().toString())
                .orElse(predicate.asPrintable());

        report(source, "biome", name, origin, target, world);
        return 1;
    }

    // ---- structure ----------------------------------------------------------------------------

    private static int locateStructure(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        ResourceOrTagKeyArgument.Result<Structure> predicate =
                ResourceOrTagKeyArgument.getResourceOrTagKey(
                        ctx, "structure", Registries.STRUCTURE, STRUCTURE_INVALID);

        // getEntry() already yields either a single reference or a named tag list.
        Registry<Structure> registry = world.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> entries = predicate.unwrap()
                .<java.util.Optional<HolderSet<Structure>>>map(
                        key -> registry.getHolder(key)
                                .map(entry -> (HolderSet<Structure>) HolderSet.direct(entry)),
                        tag -> registry.getTag(tag)
                                .map(named -> (HolderSet<Structure>) named))
                .orElseThrow(() -> STRUCTURE_INVALID.create(predicate.asPrintable()));

        Chat.warn(source, "Searching for structure " + predicate.asPrintable()
                + ". This may generate terrain and take a moment...");

        Pair<BlockPos, Holder<Structure>> found = world.getChunkSource()
                .getGenerator()
                .findNearestMapStructure(world, entries, origin, STRUCTURE_SEARCH_CHUNKS, false);

        if (found == null) {
            throw STRUCTURE_NOT_FOUND.create(predicate.asPrintable());
        }

        BlockPos target = found.getFirst();
        String name = found.getSecond().unwrapKey()
                .map(key -> key.location().toString())
                .orElse(predicate.asPrintable());

        report(source, "structure", name, origin, target, world);
        return 1;
    }

    // ---- point of interest --------------------------------------------------------------------

    /** {@code /locate poi}, replacing vanilla's. */
    private static int locatePoi(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        ResourceOrTagArgument.Result<PoiType> predicate =
                ResourceOrTagArgument.getResourceOrTag(
                        ctx, "poi", Registries.POINT_OF_INTEREST_TYPE);

        Msg.warn(source, "Searching for " + predicate.asPrintable()
                + ". Loading chunks as it goes, this may take a moment.");

        // Try what is already loaded first: usually instant, and costs nothing if it hits.
        Optional<BlockPos> found = world.getPoiManager().findClosest(
                predicate, origin, POI_SEARCH_RADIUS, PoiManager.Occupancy.ANY);

        if (found.isEmpty()) {
            found = searchWithChunkLoading(world, origin, predicate);
        }

        if (found.isEmpty()) {
            throw POI_NOT_FOUND.create(predicate.asPrintable());
        }
        report(source, "point of interest", predicate.asPrintable(), origin, found.get(), world);
        return 1;
    }

    /**
     * Widen the search by loading chunks in rings around the origin.
     *
     * Chunks are pulled to FULL so their point-of-interest data is populated, checked, then
     * released. Working outward in rings means a nearby hit still returns quickly instead of paying
     * for the whole radius every time.
     */
    private static Optional<BlockPos> searchWithChunkLoading(
            ServerLevel world, BlockPos origin,
            ResourceOrTagArgument.Result<PoiType> predicate) {
        ChunkPos centre = new ChunkPos(origin);
        List<ChunkPos> forced = new ArrayList<>();
        try {
            for (int ring = 1; ring <= POI_SEARCH_CHUNK_RINGS; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue; // perimeter of this ring only
                        }
                        ChunkPos pos = new ChunkPos(centre.x + dx, centre.z + dz);
                        if (world.hasChunk(pos.x, pos.z)) {
                            continue;
                        }
                        try {
                            world.setChunkForced(pos.x, pos.z, true);
                            forced.add(pos);
                            world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
                        } catch (Throwable t) {
                            LPLog.error("Could not load chunk " + pos + " for POI search", t);
                        }
                    }
                }

                Optional<BlockPos> hit = world.getPoiManager().findClosest(
                        predicate, origin, POI_SEARCH_RADIUS,
                        PoiManager.Occupancy.ANY);
                if (hit.isPresent()) {
                    return hit;
                }
            }
            return Optional.empty();
        } finally {
            for (ChunkPos pos : forced) {
                try {
                    world.setChunkForced(pos.x, pos.z, false);
                } catch (Throwable t) {
                    LPLog.error("Could not release forced chunk " + pos, t);
                }
            }
        }
    }

    // ---- shared output ------------------------------------------------------------------------

    /**
     * Y the teleport button should aim at for a biome or structure hit.
     *
     * The surface heightmap answers this in the overworld, but a dimension with a ceiling has
     * bedrock as its highest block, so the heightmap points at the roof. Aiming there sends the
     * teleport looking for a floor it cannot find above the roof, and it ends up at the bottom of
     * the world. Halfway up is the honest answer instead: it is the open space people build and
     * travel in, and the teleport finds the real floor from there.
     *
     * No blocks are read. A biome or structure hit is usually in terrain that does not exist yet,
     * and touching it here would generate chunks on the server thread before the player has even
     * decided to click the button.
     */
    private static int aimHeight(ServerLevel world, BlockPos target) {
        int bottom = world.getMinBuildHeight();
        int top = world.getMaxBuildHeight();

        if (world.dimensionType().hasCeiling()) {
            return bottom + ((top - bottom) / 2);
        }
        int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
        return Math.max(bottom + 1, Math.min(top - 1, surface));
    }

    /** Print the hit with a safe-teleport button. */
    private static void report(CommandSourceStack source, String kind, String name,
                               BlockPos origin, BlockPos target) {
        report(source, kind, name, origin, target, null);
    }

    /**
     *
     * @param world when given, the placeholder Y in {@code target} is replaced with the real
     *              surface height at that X/Z before the teleport button is built
     */
    private static void report(CommandSourceStack source, String kind, String name,
                               BlockPos origin, BlockPos target, ServerLevel world) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));

        BlockPos buttonTarget = target;
        if (world != null) {
            buttonTarget = new BlockPos(target.getX(), aimHeight(world, target), target.getZ());
        }
        final BlockPos tpTarget = buttonTarget;

        Msg.heading(source, "Nearest " + kind);
        Msg.field(source, "Type", name);
        source.sendSuccess(() -> Msg.detail("")
                .append(Component.literal("at " + target.getX() + " ~ " + target.getZ() + "  ")
                        .withStyle(ChatFormatting.WHITE))
                .append(TeleportService.vanillaTeleportButton(tpTarget)), false);
        Msg.note(source, "The teleport button finds somewhere safe to stand and tells you "
                + "where you landed.");
    }
}

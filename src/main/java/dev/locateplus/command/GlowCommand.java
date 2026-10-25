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
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Either;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPLog;
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.report.Msg;
import dev.locateplus.scan.ChunkAccessPolicy;
import dev.locateplus.scan.Highlights;
import dev.locateplus.scan.ScanRegion;
import dev.locateplus.util.Radius;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /glow <target> <n> chunks|blocks}
 *
 * Applies the vanilla Glowing effect to every matching entity for one minute, so they can be
 * tracked through terrain.
 */
public final class GlowCommand {

    private static final DynamicCommandExceptionType UNKNOWN_TYPE = new DynamicCommandExceptionType(
            id -> Component.literal("No entity type or entity tag matching '" + id + "'. "
                    + "Use an id (minecraft:cow), a tag (#minecraft:skeletons), "
                    + "or a selector (@e). Anything lying on the ground is minecraft:item, "
                    + "whatever it holds."));

    private GlowCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("glow")
                .requires(source -> source.hasPermission(LPConfig.get().permissionLevel()))

                // Branch 1: entity id or #tag, including modded namespaces.
                .then(RadiusArg.attach(
                        argument("type", ResourceOrTagKeyArgument
                                .resourceOrTagKey(Registries.ENTITY_TYPE))
                                .executes(ctx -> byType(ctx,
                                        Radius.ofBlocks(LPConfig.get().defaultBlockRadius()), false)),
                        false,
                        GlowCommand::byType))

                // Branch 2: any vanilla selector, or a player name.
                .then(RadiusArg.attach(
                        argument("selector", EntityArgument.entities())
                                .executes(ctx -> bySelector(ctx,
                                        Radius.ofBlocks(LPConfig.get().defaultBlockRadius()), false)),
                        false,
                        GlowCommand::bySelector));
    }

    // ---- branch 1: id / tag -------------------------------------------------------------------

    private static int byType(CommandContext<CommandSourceStack> ctx, Radius radius, boolean forceload)
            throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<EntityType<?>> predicate =
                ResourceOrTagKeyArgument.getResourceOrTagKey(
                        ctx, "type", Registries.ENTITY_TYPE, UNKNOWN_TYPE);

        Either<ResourceKey<EntityType<?>>, TagKey<EntityType<?>>> key = predicate.unwrap();
        String label = key.map(k -> k.location().toString(), tag -> "#" + tag.location());

        var missing = key.map(
                k -> !BuiltInRegistries.ENTITY_TYPE.containsKey(k),
                tag -> BuiltInRegistries.ENTITY_TYPE.getTag(tag).isEmpty());
        if (missing) {
            throw UNKNOWN_TYPE.create(label);
        }

        EntityQuery query = key.map(
                k -> EntityQuery.ofType(BuiltInRegistries.ENTITY_TYPE.get(k)),
                EntityQuery::ofTag);

        return run(ctx.getSource(), label, radius, forceload, world -> query::matches);
    }

    // ---- branch 2: selector -------------------------------------------------------------------

    private static int bySelector(CommandContext<CommandSourceStack> ctx, Radius radius,
                                  boolean forceload) throws CommandSyntaxException {
        String label = describeSelector(ctx);

        if (!label.startsWith("@") && (label.indexOf(':') >= 0 || label.startsWith("#"))) {
            throw UNKNOWN_TYPE.create(label);
        }

        return run(ctx.getSource(), label, radius, forceload, world -> {
            Collection<? extends Entity> matched;
            try {
                matched = EntityArgument.getOptionalEntities(ctx, "selector");
            } catch (CommandSyntaxException e) {
                return entity -> false;
            }
            Set<UUID> ids = new HashSet<>(Math.max(16, matched.size() * 2));
            for (Entity entity : matched) {
                ids.add(entity.getUUID());
            }
            return entity -> ids.contains(entity.getUUID());
        });
    }

    /** The raw selector text the player typed, for chat output. */
    private static String describeSelector(CommandContext<CommandSourceStack> ctx) {
        for (var node : ctx.getNodes()) {
            if ("selector".equals(node.getNode().getName())) {
                var range = node.getRange();
                String input = ctx.getInput();
                int start = Math.max(0, Math.min(range.getStart(), input.length()));
                int end = Math.max(start, Math.min(range.getEnd(), input.length()));
                return input.substring(start, end);
            }
        }
        return "selection";
    }

    // ---- shared implementation ----------------------------------------------------------------

    /** Builds the match test once the world is in its final (possibly force-loaded) state. */
    @FunctionalInterface
    private interface MatcherFactory {
        Predicate<Entity> create(ServerLevel world);
    }

    private static int run(CommandSourceStack source, String label, Radius radius,
                           boolean forceload, MatcherFactory factory) {
        ServerLevel world = source.getLevel();
        Vec3 origin = source.getPosition();
        BlockPos originBlock = BlockPos.containing(origin);
        ScanRegion region = ScanRegion.forRadius(originBlock, radius, forceload);

        // In block mode the typed distance is the real bound.
        boolean byBlocks = radius.typedUnit() == Radius.Unit.BLOCKS;
        int blocks = byBlocks ? radius.blocks() : region.blockExtent() + 16;
        if (!LocateCommand.confirmForceload(source, world, region, forceload)) {
            return 0;
        }

        ChunkAccessPolicy access = new ChunkAccessPolicy(world, forceload);
        try {
            if (forceload) {
                for (ChunkPos pos : region.chunks()) {
                    access.acquire(pos);
                }
            }

            Predicate<Entity> matches = factory.create(world);

            AABB box = new AABB(
                    origin.x - blocks, LPConfig.get().scanMinY(world), origin.z - blocks,
                    origin.x + blocks, LPConfig.get().scanMaxY(world) + 1.0,
                    origin.z + blocks);
            double radiusSq = (double) blocks * blocks;

            List<Entity> found = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();

            for (Entity entity : world.getEntities((Entity) null, box, e -> true)) {
                if (BlockBeacon.isMarker(entity)) {
                    continue; // this mod's own block marker
                }
                consider(entity, matches, origin, radiusSq, byBlocks, region, seen, found);
            }
            // Players are tracked separately from chunk entity storage; sweep them in too.
            for (Entity player : world.players()) {
                consider(player, matches, origin, radiusSq, byBlocks, region, seen, found);
            }

            if (found.isEmpty()) {
                Msg.warn(source, "No " + label + " within " + Msg.area(radius) + ".");
                return 0;
            }

            Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
            int glowed = 0;
            for (Entity entity : found) {
                try {
                    Highlights.glow(world, entity);
                    counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), 1, Integer::sum);
                    glowed++;
                } catch (Throwable t) {
                    LPLog.error("Failed to apply glow to " + entity.getType(), t);
                }
            }

            int total = glowed;
            Msg.success(source, "Glowing " + Msg.count(total, "entity", "entities")
                    + " matching " + label + " for "
                    + Msg.count(LPConfig.get().glowDurationTicks() / 20, "second") + ".");
            Msg.field(source, "Searched", Msg.area(radius));

            List<Map.Entry<ResourceLocation, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort(Map.Entry.<ResourceLocation, Integer>comparingByValue().reversed());
            int shown = Math.min(LPConfig.get().chatTopN(), sorted.size());
            for (int i = 0; i < shown; i++) {
                Map.Entry<ResourceLocation, Integer> entry = sorted.get(i);
                int rank = i + 1;
                source.sendSuccess(() -> Msg.result(rank, entry.getKey().toString(),
                        entry.getValue(), -1), false);
            }
            if (sorted.size() > shown) {
                Msg.more(source, sorted.size() - shown, "types");
            }
            return total;
        } finally {
            access.close();
        }
    }

    /**
     * Add one entity to the result list if it matches and has not been seen already.
     *
     * @param byBlocks true when the player gave a block distance, so the radius is the bound;
     *                 otherwise the bound is membership of {@code region}
     */
    private static void consider(Entity entity, Predicate<Entity> matches,
                                 Vec3 origin, double radiusSq, boolean byBlocks,
                                 ScanRegion region, Set<UUID> seen, List<Entity> out) {
        if (entity == null || entity.isRemoved() || !seen.add(entity.getUUID())) {
            return;
        }
        if (!matches.test(entity)) {
            return;
        }
        boolean inRange = byBlocks
                ? horizontalDistanceSq(origin, entity) <= radiusSq
                : region.containsPosition(entity.getX(), entity.getZ());
        if (inRange) {
            out.add(entity);
        }
    }

    /** Horizontal (X/Z) distance only, ignoring Y. */
    private static double horizontalDistanceSq(Vec3 origin, Entity entity) {
        double dx = origin.x - entity.getX();
        double dz = origin.z - entity.getZ();
        return dx * dx + dz * dz;
    }

}

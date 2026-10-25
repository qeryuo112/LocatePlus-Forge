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
import com.mojang.brigadier.context.CommandContext;
import dev.locateplus.core.LPLog;
import dev.locateplus.inspect.BlockInspector;
import dev.locateplus.inspect.ContainerInspector;
import dev.locateplus.inspect.EntityInspector;
import dev.locateplus.inspect.InspectLine;
import dev.locateplus.report.Chat;
import dev.locateplus.report.Msg;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /lp inspect <x> <y> <z>}, a full report on one block position.
 *
 * Covers the block itself (state, redstone, light, spawning, growth), its block entity (container
 * contents, machine progress), and every entity standing there (equipment, effects, villager data,
 * pet ownership).
 */
public final class InspectCommand {

    private InspectCommand() {
    }

    /** The {@code inspect} subtree, attached to {@code /lp} by {@link HelpCommand}. */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("inspect")
                .then(argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> inspect(ctx, false))
                        .then(literal("forceload")
                                .executes(ctx -> inspect(ctx, true))));
    }

    /**
     * Read everything about one position.
     *
     * The whole body is guarded. This command asks a block, its block entity and every entity on
     * the tile to describe itself, and a modded one that throws while doing so would otherwise
     * take the server down with it. A failed inspection is worth a message, not a crash.
     */
    private static int inspect(CommandContext<CommandSourceStack> ctx, boolean forceload) {
        try {
            return inspectGuarded(ctx, forceload);
        } catch (Throwable t) {
            LPLog.error("Inspect failed", t);
            Chat.error(ctx.getSource(), "Could not inspect that position: " + describe(t));
            return 0;
        }
    }

    /** Short, readable form of a failure, since a raw toString is mostly package names. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static int inspectGuarded(CommandContext<CommandSourceStack> ctx, boolean forceload) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();

        // Brigadier has already parsed and validated the coordinates by the time this runs, and
        // anything that does go wrong further down is caught by inspect() above.
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

        boolean wasLoaded = world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (!wasLoaded) {
            if (!forceload) {
                Chat.warn(source, "Chunk at " + Chat.coords(pos) + " is not loaded. "
                        + "Add 'forceload' to load it for this inspection.");
                return 0;
            }
            // Pull the chunk to FULL so block states, block entities and entities are all real.
            Chat.warn(source, "Force-loading the chunk at " + Chat.coords(pos)
                    + ". This may generate terrain.");
            try {
                world.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                        ChunkStatus.FULL, true);
            } catch (Throwable t) {
                Chat.error(source, "Could not load that chunk: " + t.getMessage());
                return 0;
            }
        }

        InspectLine out = new InspectLine();
        BlockState state = world.getBlockState(pos);

        // ---- block ----------------------------------------------------------------------------
        out.section("Block");
        out.field("Id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        out.field("Dimension", world.dimension().location().toString());

        Map<Property<?>, Comparable<?>> properties = state.getValues();
        if (!properties.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            properties.forEach((property, value) -> {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(property.getName()).append('=').append(value);
            });
            out.field("State", sb.toString());
        }
        if (!state.getFluidState().isEmpty()) {
            out.field("Fluid", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
        }
        float hardness = state.getDestroySpeed(world, pos);
        if (hardness >= 0) {
            out.field("Hardness", String.format("%.1f", hardness));
        } else {
            out.field("Hardness", "unbreakable", ChatFormatting.YELLOW);
        }

        // ---- redstone, light, growth ----------------------------------------------------------
        BlockInspector.redstone(out, world, pos, state);
        BlockInspector.lightAndSpawning(out, world, pos, state);
        BlockInspector.growth(out, state);

        // ---- block entity ---------------------------------------------------------------------
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null) {
            ResourceLocation beId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            out.section("Block entity");
            out.field("Type", beId == null ? "unknown" : beId.toString());

            if (ContainerInspector.isContainer(blockEntity)) {
                ContainerInspector.contents(out, blockEntity);
            }
            ContainerInspector.machine(out, blockEntity);
        }

        // ---- entities -------------------------------------------------------------------------
        appendEntities(out, world, pos);

        // ---- send -----------------------------------------------------------------------------
        Msg.heading(source, "Inspect");
        Msg.field(source, "Position", Msg.coords(pos));
        for (Component line : out.lines()) {
            source.sendSuccess(() -> line, false);
        }
        if (!wasLoaded) {
            source.sendSuccess(() -> Component.literal(
                            "Chunk was force-loaded for this inspection; it may unload again.")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    /**
     * Group entities at this position by type.
     *
     * For each type: report the count, then expand the single most informative individual, see
     * {@link #informationScore}. A hopper-fed mob farm with 200 zombies produces three lines, not
     * six hundred.
     */
    private static void appendEntities(InspectLine out, ServerLevel world, BlockPos pos) {
        AABB box = new AABB(pos);
        List<Entity> found = new ArrayList<>();
        try {
            found.addAll(world.getEntities((Entity) null, box,
                    entity -> !BlockBeacon.isMarker(entity)));
        } catch (Throwable t) {
            return;
        }

        // Players are tracked separately from chunk entity storage, so getOtherEntities never
        // returns them.
        for (ServerPlayer player : world.players()) {
            if (box.intersects(player.getBoundingBox()) && !found.contains(player)) {
                found.add(player);
            }
        }

        out.section("Entities");
        if (found.isEmpty()) {
            out.field("Count", "0", ChatFormatting.DARK_GRAY);
            return;
        }

        Map<ResourceLocation, List<Entity>> byType = new LinkedHashMap<>();
        for (Entity entity : found) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            byType.computeIfAbsent(id, key -> new ArrayList<>()).add(entity);
        }

        out.field("Total", found.size() + " entit" + (found.size() == 1 ? "y" : "ies")
                + " in " + byType.size() + " type" + (byType.size() == 1 ? "" : "s"));

        for (Map.Entry<ResourceLocation, List<Entity>> entry : byType.entrySet()) {
            List<Entity> group = entry.getValue();
            Entity best = group.stream()
                    .max(Comparator.comparingInt(InspectCommand::informationScore))
                    .orElse(group.get(0));

            out.section(entry.getKey() + (group.size() > 1 ? "  x" + group.size() : ""));
            if (group.size() > 1) {
                out.field("Count", String.valueOf(group.size()), ChatFormatting.AQUA);
                out.field("Showing", "the most detailed of the " + group.size(),
                        ChatFormatting.DARK_GRAY);
            }
            describeOne(out, best, world);
        }
    }

    /**
     * How interesting an entity is, used to pick which of a group to expand.
     *
     * Weighted so that a named, geared, effect-laden mob always beats a plain one. Ties fall back
     * to whichever came first in the list, which is stable enough for a diagnostic command.
     */
    private static int informationScore(Entity entity) {
        int score = 0;
        try {
            if (entity.hasCustomName()) {
                score += 100;
            }
            if (!entity.getTags().isEmpty()) {
                score += 40;
            }
            if (entity.isInvulnerable()) {
                score += 10;
            }
            if (entity instanceof LivingEntity living) {
                score += living.getActiveEffects().size() * 25;
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (!living.getItemBySlot(slot).isEmpty()) {
                        score += 20;
                    }
                }
                // A damaged mob is usually the one being investigated.
                if (living.getHealth() < living.getMaxHealth()) {
                    score += 15;
                }
            }
            if (entity instanceof Villager villager) {
                score += 50 + villager.getVillagerData().getLevel() * 5;
            }
            if (entity instanceof TamableAnimal tameable
                    && tameable.isTame()) {
                score += 60;
            }
            if (entity instanceof ItemEntity item) {
                score += item.getItem().getCount();
            }
        } catch (Throwable ignored) {
            // a misbehaving modded entity simply scores low
        }
        return score;
    }

    /** Core identity fields for one entity, followed by the detailed sections. */
    private static void describeOne(InspectLine out, Entity entity, ServerLevel world) {
        out.field("UUID", entity.getStringUUID());
        out.field("Name", entity.getName().getString()
                + (entity.getCustomName() != null
                ? "  (\"" + entity.getCustomName().getString() + "\")" : ""));
        out.field("Position", String.format("%.3f %.3f %.3f",
                entity.getX(), entity.getY(), entity.getZ()));

        if (entity instanceof LivingEntity living) {
            out.field("Health", String.format("%.1f / %.1f",
                    living.getHealth(), living.getMaxHealth()),
                    living.getHealth() < living.getMaxHealth() / 2 ? ChatFormatting.RED : ChatFormatting.WHITE);
        }
        if (entity instanceof ItemEntity item) {
            var stack = item.getItem();
            out.field("Item", stack.getCount() + "x "
                    + BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (item.getAge() > 0) {
                out.field("Age", Msg.count(item.getAge(), "tick"));
            }
        }
        if (entity.isCurrentlyGlowing()) {
            out.field("Glowing", "true", ChatFormatting.AQUA);
        }

        EntityInspector.describe(out, entity, world);
    }
}

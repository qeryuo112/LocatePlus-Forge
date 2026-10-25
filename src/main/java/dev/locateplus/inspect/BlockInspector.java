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
package dev.locateplus.inspect;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.Locale;

/**
 * Block-level {@code /lp inspect} sections: redstone, light and mob spawning, and growth stages.
 *
 * Everything here reads state that is already in memory. Nothing is computed by simulation, so a
 * single inspect is effectively free even on a busy server.
 */
public final class BlockInspector {

    private BlockInspector() {
    }

    // ---- redstone -----------------------------------------------------------------------------

    /** Signal strength, powered status, and which way a component points. */
    public static void redstone(InspectLine out, ServerLevel world, BlockPos pos, BlockState state) {
        InspectLine section = new InspectLine();

        int received = world.getBestNeighborSignal(pos);
        boolean receiving = world.hasNeighborSignal(pos);

        if (received > 0 || receiving) {
            section.field("Receiving power", received + " / 15",
                    received > 0 ? ChatFormatting.RED : ChatFormatting.GRAY);
        }

        // Blocks that carry their own signal level: redstone wire, repeaters, comparators, etc.
        if (state.hasProperty(BlockStateProperties.POWER)) {
            int power = state.getValue(BlockStateProperties.POWER);
            section.field("Signal strength", power + " / 15",
                    power > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY);
        }
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            boolean powered = state.getValue(BlockStateProperties.POWERED);
            section.field("Powered", String.valueOf(powered),
                    powered ? ChatFormatting.RED : ChatFormatting.DARK_GRAY);
        }
        if (state.hasProperty(BlockStateProperties.LIT)) {
            boolean lit = state.getValue(BlockStateProperties.LIT);
            section.field("Lit / active", String.valueOf(lit),
                    lit ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY);
        }

        // Emitted power, sampled on each face.
        if (state.isSignalSource()) {
            StringBuilder weak = new StringBuilder();
            StringBuilder strong = new StringBuilder();
            for (Direction dir : Direction.values()) {
                int w = state.getSignal(world, pos, dir);
                int s = state.getDirectSignal(world, pos, dir);
                if (w > 0) {
                    append(weak, dir.getName() + " " + w);
                }
                if (s > 0) {
                    append(strong, dir.getName() + " " + s);
                }
            }
            if (weak.length() > 0) {
                section.field("Emits (weak)", weak.toString(), ChatFormatting.RED);
            }
            if (strong.length() > 0) {
                section.field("Emits (strong)", strong.toString(), ChatFormatting.RED);
            }
        }

        // Which way the component points, and where its output lands.
        Direction facing = null;
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            facing = state.getValue(BlockStateProperties.FACING);
        }
        if (facing != null) {
            BlockPos target = pos.relative(facing);
            String targetId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(world.getBlockState(target).getBlock()).toString();
            section.field("Facing", facing.getName()
                    + "  -> " + target.getX() + " " + target.getY() + " " + target.getZ()
                    + " (" + targetId + ")");
        }

        // Repeater delay and comparator mode are the two settings people forget to check.
        if (state.hasProperty(BlockStateProperties.DELAY)) {
            section.field("Repeater delay", state.getValue(BlockStateProperties.DELAY) + " tick(s)");
        }
        if (state.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
            section.field("Comparator mode",
                    state.getValue(BlockStateProperties.MODE_COMPARATOR).getSerializedName());
        }
        if (state.hasProperty(BlockStateProperties.LOCKED) && state.getValue(BlockStateProperties.LOCKED)) {
            section.field("Locked", "true", ChatFormatting.YELLOW);
        }

        if (!section.isEmpty()) {
            out.section("Redstone");
            out.lines().addAll(section.lines());
        }
    }

    private static void append(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(value);
    }

    // ---- light and spawning -------------------------------------------------------------------

    /** Light levels and whether a hostile mob could spawn here. */
    public static void lightAndSpawning(InspectLine out, ServerLevel world, BlockPos pos, BlockState state) {
        out.section("Light & spawning");

        int blockLight = world.getBrightness(LightLayer.BLOCK, pos);
        int skyLight = world.getBrightness(LightLayer.SKY, pos);
        int emitted = state.getLightEmission();

        out.field("Block light", blockLight + " / 15");
        out.field("Sky light", skyLight + " / 15");
        if (emitted > 0) {
            out.field("Emits light", emitted + " / 15", ChatFormatting.YELLOW);
        }

        BlockPos below = pos.below();
        BlockState floor = world.getBlockState(below);

        boolean floorSolid = floor.isFaceSturdy(world, below, Direction.UP);
        boolean roomHere = state.getCollisionShape(world, pos).isEmpty();
        boolean roomAbove = world.getBlockState(pos.above()).getCollisionShape(world, pos.above()).isEmpty();
        boolean floorBlocked = floor.is(BlockTags.LEAVES)
                || floor.is(Blocks.BEDROCK)
                || floor.is(Blocks.BARRIER);
        boolean darkEnough = blockLight <= 0;

        boolean canSpawn = floorSolid && roomHere && roomAbove && !floorBlocked && darkEnough;

        out.field("Hostile mobs can spawn", canSpawn ? "YES" : "no",
                canSpawn ? ChatFormatting.RED : ChatFormatting.GREEN);

        if (!canSpawn) {
            String reason;
            if (!floorSolid) {
                reason = "no solid block below";
            } else if (floorBlocked) {
                reason = "floor material blocks spawning";
            } else if (!roomHere || !roomAbove) {
                reason = "not enough headroom (needs 2 free blocks)";
            } else {
                reason = "block light is " + blockLight + " (needs 0)";
            }
            out.field("  reason", reason, ChatFormatting.DARK_GRAY);
        }

        // Passive animals use a different rule: grass, daylight, and no light requirement.
        if (floor.is(Blocks.GRASS_BLOCK) && roomHere && skyLight >= 9) {
            out.field("Passive animals can spawn", "YES (grass, sky light "
                    + skyLight + ")", ChatFormatting.YELLOW);
        }
    }

    // ---- growth -------------------------------------------------------------------------------

    /**
     * Growth stage for crops, stems, saplings and anything else with an {@code age} property.
     *
     * Discovered generically from the block state rather than from a hardcoded block list, so
     * modded crops report correctly too.
     */
    public static void growth(InspectLine out, BlockState state) {
        IntegerProperty age = null;
        for (Property<?> property : state.getValues().keySet()) {
            if (property instanceof IntegerProperty intProperty && "age".equals(intProperty.getName())) {
                age = intProperty;
                break;
            }
        }
        if (age == null) {
            // Sapling growth uses "stage"; bamboo and a few others do too.
            for (Property<?> property : state.getValues().keySet()) {
                if (property instanceof IntegerProperty intProperty && "stage".equals(intProperty.getName())) {
                    out.section("Growth");
                    Collection<Integer> values = intProperty.getPossibleValues();
                    int max = values.stream().mapToInt(Integer::intValue).max().orElse(0);
                    int current = state.getValue(intProperty);
                    out.field("Stage", current + " / " + max);
                    out.field("Status", current >= max ? "ready to grow" : "growing",
                            current >= max ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
                    return;
                }
            }
            return;
        }

        out.section("Growth");
        Collection<Integer> values = age.getPossibleValues();
        int max = values.stream().mapToInt(Integer::intValue).max().orElse(0);
        int current = state.getValue(age);
        boolean mature = current >= max;

        out.field("Age", current + " / " + max);
        out.field("Progress", String.format(Locale.US, "%.0f%%",
                max == 0 ? 100.0 : (current * 100.0 / max)));
        out.field("Status", mature ? "FULLY GROWN" : "still growing",
                mature ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }
}

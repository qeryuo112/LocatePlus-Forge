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
package dev.locateplus.teleport;

import dev.locateplus.core.LPConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds somewhere a player can stand without dying.
 *
 * The landing Y comes from the floor's actual collision shape, so a player lands on top of a slab
 * rather than inside it. Shallow water at foot level is fine (you wade, you do not drown); water
 * over the head is not.
 */
public final class SafeLocator {

    /** Positions the search may examine before giving up. */
    private static final int DEFAULT_BUDGET = 2_000_000;

    /**
     * Furthest shell the search will visit.
     *
     * Each shell is the surface of a cube, so the work grows with the square of the reach: sixteen
     * covers thirty-six thousand positions, sixty-four covers two million. A landing further out
     * than this is not a near miss and the column and surface fallbacks answer it better, so the
     * reach is kept where the cost is still small.
     */
    private static final int MAX_SHELL = 16;

    /**
     * Minimum top-surface height for a block to count as a floor. A bottom slab is 0.5, a snow
     * layer is 0.125; both are fine to stand on.
     */
    private static final double MIN_FLOOR_HEIGHT = 0.1;

    /**
     * Furthest a column is followed down before the search gives up on it.
     *
     * Enough to reach the bottom of a world from its ceiling, and no more. Five columns are walked
     * on a miss and each block read can pull a chunk section into cache, so a bound that grows
     * without limit turns one teleport into seconds of work.
     */
    private static final int MAX_DROP = 512;

    /** Height of the floor inside an empty composter, from its collision shape. */
    private static final double COMPOSTER_FLOOR = 0.125;

    /** Height of the floor inside a cauldron, from its collision shape. */
    private static final double CAULDRON_FLOOR = 0.25;

    private SafeLocator() {
    }

    /** Nearest spot to {@code requested} that a player can stand in, by true 3D distance. */
    public static SafeSpot find(ServerLevel world, BlockPos requested) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // The position itself, then the space directly on top of it. Both are exact answers, so
        // nothing closer exists and there is no reason to look further. This is what lands you on
        // a composter, a chest or a slab rather than beside it.
        SafeSpot exact = findAtTarget(world, requested, cursor);
        if (exact != null) {
            return exact;
        }

        int budget = searchBudget();
        int examined = 0;

        SafeSpot best = null;
        double bestScore = Double.MAX_VALUE;

        for (int shell = 0; shell <= MAX_SHELL; shell++) {
            // Every position in a later shell is at least this far away, so once that exceeds what
            // has already been found there is nothing left worth looking at.
            if (best != null && shell > bestScore) {
                break;
            }

            for (int dx = -shell; dx <= shell; dx++) {
                for (int dz = -shell; dz <= shell; dz++) {
                    for (int dy = -shell; dy <= shell; dy++) {
                        // Only the surface of this shell is new; everything inside was covered by
                        // an earlier, closer pass.
                        int reach = Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy));
                        if (reach != shell) {
                            continue;
                        }

                        int x = requested.getX() + dx;
                        int y = requested.getY() + dy;
                        int z = requested.getZ() + dz;
                        if (y <= world.getMinBuildHeight() || y >= world.getMaxBuildHeight()) {
                            continue; // outside the world, nothing to read there
                        }

                        // Positions in unloaded chunks count against the budget as well.
                        examined++;

                        Double landing = standingHeight(world, cursor.set(x, y, z));
                        if (landing == null) {
                            continue;
                        }

                        double score = Math.sqrt((double) dx * dx + (double) dy * dy
                                + (double) dz * dz);
                        if (score < bestScore) {
                            bestScore = score;
                            best = new SafeSpot(new Vec3(x + 0.5, landing, z + 0.5), requested);
                        }
                    }
                }
            }
            if (examined >= budget) {
                break;
            }
        }
        // Five columns are worth following down: the target's own, and the four touching it. The
        // target's own is what a pillar with an unstandable tip needs, since the block holding
        // that tip up is directly beneath it. The neighbours cover a ledge beside the target.
        //
        // These run even when the shell search found something, because what it found may be far
        // away horizontally. A pillar is the case that matters: standing on the pillar itself,
        // however far down, beats a rooftop thirty blocks sideways at a similar distance.
        SafeSpot column = bestColumn(world, requested, cursor);

        if (best != null && column != null) {
            // Prefer whichever is genuinely nearer to what was asked for, with the column winning
            // a tie: it keeps the player over the same X and Z they named.
            return column.distance() <= best.distance() ? column : best;
        }
        if (best != null) {
            return best;
        }
        if (column != null) {
            return column;
        }

        // Nothing anywhere near.
        return surfaceFallback(world, requested);
    }

    /**
     * The highest floor among the target's own column and the four touching it.
     *
     * Highest wins because that is the one nearest what was asked for. Taking the first column to
     * answer would drop the player to the ground beside a pillar rather than onto the pillar.
     */
    private static SafeSpot bestColumn(ServerLevel world, BlockPos requested,
                                       BlockPos.MutableBlockPos cursor) {
        SafeSpot highest = null;
        int highestY = Integer.MIN_VALUE;

        SafeSpot below = findBelow(world, requested, cursor);
        if (below != null) {
            highest = below;
            highestY = below.blockPos().getY();
        }

        SafeSpot alongside = findAlongside(world, requested, cursor);
        if (alongside != null && alongside.blockPos().getY() > highestY) {
            highest = alongside;
            highestY = alongside.blockPos().getY();
        }

        // A solid pillar answers neither of those, since there is nothing to stand in or beside
        // until its foot. This finds where it meets the ground.
        SafeSpot foot = findColumnFoot(world, requested, cursor);
        if (foot != null && foot.blockPos().getY() > highestY) {
            highest = foot;
        }
        return highest;
    }

    /**
     * The requested position, or the space immediately on top of it.
     *
     * Both count as landing on what was asked for: standing in the block when there is room, and
     * standing on it when there is not. An empty composter takes the first path, a full one the
     * second, and a chest or a slab the second.
     *
     * A block on the top layer of the world has nothing above it to stand on, so neither works and
     * the ordinary search picks a neighbour instead.
     *
     * @return the spot, or null when neither is standable
     */
    private static SafeSpot findAtTarget(ServerLevel world, BlockPos requested,
                                         BlockPos.MutableBlockPos cursor) {
        int x = requested.getX();
        int z = requested.getZ();

        Double atTarget = standingHeight(world, cursor.set(x, requested.getY(), z));
        if (atTarget != null) {
            return new SafeSpot(new Vec3(x + 0.5, atTarget, z + 0.5), requested);
        }

        int above = requested.getY() + 1;
        if (above < world.getMaxBuildHeight() && !world.getBlockState(requested).isAir()) {
            Double onTop = standingHeight(world, cursor.set(x, above, z));
            if (onTop != null) {
                return new SafeSpot(new Vec3(x + 0.5, onTop, z + 0.5), requested);
            }
        }

        return null;
    }

    /**
     * The highest standable spot among the four columns touching {@code requested}.
     *
     * For a block that cannot be stood on or inside: a filled composter, powder snow, a chest, or
     * anything solid whose top is the world ceiling. Each neighbouring column is followed down and
     * the highest floor wins, so a pillar with one block at its tip answers with the ledge beside
     * it, or failing that the ground it stands on.
     */
    private static SafeSpot findAlongside(ServerLevel world, BlockPos requested,
                                          BlockPos.MutableBlockPos cursor) {
        int x = requested.getX();
        int y = requested.getY();
        int z = requested.getZ();
        int floor = Math.max(world.getMinBuildHeight() + 1, y - MAX_DROP);

        SafeSpot best = null;
        int bestY = Integer.MIN_VALUE;

        for (Direction side : Direction.Plane.HORIZONTAL) {
            int nx = x + side.getStepX();
            int nz = z + side.getStepZ();
            for (int ny = y; ny >= floor; ny--) {
                Double landing = standingHeight(world, cursor.set(nx, ny, nz));
                if (landing == null) {
                    continue;
                }
                if (ny > bestY) {
                    bestY = ny;
                    best = new SafeSpot(new Vec3(nx + 0.5, landing, nz + 0.5), requested);
                }
                break; // the first floor going down is the highest one this column offers
            }
        }
        return best;
    }

    /**
     * First floor straight down the requested column.
     *
     * The last thing tried, and only when nothing else in range was standable. A pillar whose top
     * cannot be stood on has one sensible answer, the ground it stands on, and that may be a long
     * way down. Reaching for it earlier would drop you off a ledge whenever a spot two blocks
     * sideways was the obvious choice.
     *
     * The drop runs as far as {@link #MAX_DROP}, which is the world. Anything closer has already
     * been ruled out by the time this is reached.
     *
     * @return the spot, or null when nothing in the column is standable
     */
    private static SafeSpot findBelow(ServerLevel world, BlockPos requested,
                                      BlockPos.MutableBlockPos cursor) {
        int x = requested.getX();
        int z = requested.getZ();
        int bottom = Math.max(world.getMinBuildHeight() + 1, requested.getY() - MAX_DROP);

        boolean throughSolid = false;
        for (int y = requested.getY() - 1; y > bottom; y--) {
            Double landing = standingHeight(world, cursor.set(x, y, z));
            if (landing != null) {
                // Having passed through solid rock to get here means the column was a pillar or a
                // wall, and this floor is somewhere underneath it rather than at its foot. The
                // ground the pillar stands on is the answer a player means, and that is beside it.
                if (throughSolid) {
                    return null;
                }
                return new SafeSpot(new Vec3(x + 0.5, landing, z + 0.5), requested);
            }
            if (!world.getBlockState(cursor.set(x, y, z)).isAir()) {
                throughSolid = true;
            }
        }
        return null;
    }

    /**
     * The foot of a solid column: the ground beside the lowest block of whatever fills it.
     *
     * Walking down the inside of a pillar finds nothing, because solid rock is not standable, and
     * carrying on regardless lands in the first cave below it. What someone means by the bottom of
     * a pillar is where it meets the ground, so the column is followed down to its last solid
     * block and the search steps out from there.
     *
     * @return the spot, or null when the column is not solid or nothing beside its foot works
     */
    private static SafeSpot findColumnFoot(ServerLevel world, BlockPos requested,
                                           BlockPos.MutableBlockPos cursor) {
        int x = requested.getX();
        int z = requested.getZ();
        int bottom = Math.max(world.getMinBuildHeight() + 1, requested.getY() - MAX_DROP);

        int lastSolid = Integer.MIN_VALUE;
        for (int y = requested.getY(); y > bottom; y--) {
            if (world.getBlockState(cursor.set(x, y, z)).isAir()) {
                if (lastSolid != Integer.MIN_VALUE) {
                    break; // the column has ended, so its foot is the block above this gap
                }
                continue;
            }
            lastSolid = y;
        }
        if (lastSolid == Integer.MIN_VALUE) {
            return null;
        }

        // Step out at the foot, and a little above it, since the ground there may be uneven.
        for (int dy = 0; dy <= 2; dy++) {
            SafeSpot beside = besideAt(world, requested, cursor, lastSolid + dy);
            if (beside != null) {
                return beside;
            }
        }
        return null;
    }

    /** A standable spot in one of the four blocks touching the column at height {@code y}. */
    private static SafeSpot besideAt(ServerLevel world, BlockPos requested,
                                     BlockPos.MutableBlockPos cursor, int y) {
        if (y <= world.getMinBuildHeight() || y >= world.getMaxBuildHeight()) {
            return null;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int nx = requested.getX() + side.getStepX();
            int nz = requested.getZ() + side.getStepZ();
            Double landing = standingHeight(world, cursor.set(nx, y, nz));
            if (landing != null) {
                return new SafeSpot(new Vec3(nx + 0.5, landing, nz + 0.5), requested);
            }
        }
        return null;
    }

    /** Positions the search may examine before falling back. See {@code safetp_search_budget}. */
    private static int searchBudget() {
        try {
            return Math.max(1_000, LPConfig.get().safeTpSearchBudget());
        } catch (Throwable t) {
            return DEFAULT_BUDGET;
        }
    }

    /** Surface of the requested column, used only when everything else failed. */
    private static SafeSpot surfaceFallback(ServerLevel world, BlockPos requested) {
        int x = requested.getX();
        int z = requested.getZ();
        if (!world.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        // The heightmap gives the position just above the highest block, which sits one past the
        // ceiling for a build on the height limit. Clamping keeps that case usable rather than
        // discarding it.
        int surface = Math.min(world.getMaxBuildHeight() - 1,
                world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        if (surface <= world.getMinBuildHeight()) {
            return null;
        }
        Double landing = standingHeight(world, new BlockPos(x, surface, z));
        if (landing == null) {
            return null;
        }
        return new SafeSpot(new Vec3(x + 0.5, landing, z + 0.5), requested);
    }

    /**
     * Exact Y a player's feet would rest at when standing in {@code feet}, or {@code null} if the
     * position is not safe.
     *
     * Returning the height rather than a boolean is what lets a player land cleanly on top of a
     * slab or stair instead of being placed inside it.
     */
    public static Double standingHeight(ServerLevel world, BlockPos feet) {
        if (!world.hasChunk(feet.getX() >> 4, feet.getZ() >> 4)) {
            return null;
        }
        BlockPos head = feet.above();
        BlockPos ground = feet.below();
        if (ground.getY() < world.getMinBuildHeight() || feet.getY() >= world.getMaxBuildHeight()) {
            return null;
        }

        BlockState feetState = world.getBlockState(feet);
        BlockState groundState = world.getBlockState(ground);

        // The block above the world ceiling is not a block at all, and nothing can be inside it.
        // Treating that as blocked would refuse the whole top layer of the world, where a build on
        // the height limit puts its highest chest, composter or ladder.
        boolean headInWorld = head.getY() < world.getMaxBuildHeight();
        BlockState headState = headInWorld ? world.getBlockState(head) : Blocks.AIR.defaultBlockState();

        // Body space must be clear of anything you would suffocate inside.
        if (!isPassable(world, feet, feetState)
                || (headInWorld && !isPassable(world, head, headState))) {
            return null;
        }
        if (isHarmful(feetState) || isHarmful(headState) || isHarmful(groundState)) {
            return null;
        }

        // Wading through shallow water is fine; being fully submerged is not.
        if (!headState.getFluidState().isEmpty()) {
            return null;
        }
        if (feetState.getFluidState().is(FluidTags.LAVA)) {
            return null;
        }

        // A hollow block holds you up with its own inside floor, so it is the floor and whatever
        // is beneath it does not matter.
        Double inside = standInside(feetState);
        if (inside != null) {
            return feet.getY() + inside;
        }

        // The floor must offer a surface to stand on, but need not be a full cube.
        if (groundState.isAir()) {
            return null;
        }
        VoxelShape groundShape = groundState.getCollisionShape(world, ground);
        if (groundShape.isEmpty()) {
            return null;
        }
        double surface = groundShape.max(Direction.Axis.Y);
        if (surface < MIN_FLOOR_HEIGHT) {
            return null;
        }

        // Feet rest on top of whatever the floor block actually presents.
        return ground.getY() + surface;
    }

    /**
     * Whether a player's body can occupy {@code pos}.
     *
     * Air and anything with no collision box, grass, torches, signs, water, are all fine. So is a
     * hollow block that is empty: see {@link #standInside}.
     */
    private static boolean isPassable(ServerLevel world, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return standInside(state) != null;
    }

    /**
     * Height a player's feet rest at when standing inside a hollow block, or null when they cannot.
     *
     * Whether one of these can be entered depends on what is in it rather than on its shape. An
     * empty composter is a box you drop into; fill it and the compost is what you stand on, which
     * is the top of the block. A cauldron is the same idea: empty or holding water you can get in,
     * holding lava you cannot get in at all.
     *
     * @return the offset within the block, or null when the block cannot be stood inside
     */
    private static Double standInside(BlockState state) {
        if (state.is(Blocks.COMPOSTER)) {
            // A composter fills from level 0 up to level 8, where the last is ready to harvest.
            // Anything above empty leaves too little room, so it is stood on rather than in.
            return state.getValue(ComposterBlock.LEVEL) == 0 ? COMPOSTER_FLOOR : null;
        }
        if (state.is(Blocks.CAULDRON)) {
            return CAULDRON_FLOOR;
        }
        if (state.is(Blocks.WATER_CAULDRON)) {
            // Standing in the water is fine. The level only changes how deep it is.
            return CAULDRON_FLOOR;
        }
        // A lava cauldron is refused by isHarmful, and powder snow drops you through, so neither
        // reaches here. Every other block with a collision box is solid as far as this is
        // concerned.
        return null;
    }

    /**
     * Whether touching this block would hurt, burn, freeze or trap a player.
     *
     * Taken from what the block actually does on collision rather than from a list of names:
     * everything here either damages, sets alight, freezes, or moves the player somewhere they did
     * not ask to go. Blocks that merely slow or cushion, honey, slime, cobweb, a lily pad, are all
     * fine to stand in and are deliberately absent.
     */
    private static boolean isHarmful(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }

        // Fire and soul fire. The tag holds only those two, so a campfire has to be named.
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) {
            // An unlit campfire is just a block to stand on.
            return !state.hasProperty(BlockStateProperties.LIT) || state.getValue(BlockStateProperties.LIT);
        }
        if (state.is(BlockTags.CANDLES) || state.is(BlockTags.CANDLE_CAKES)) {
            return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
        }

        // Burns on contact.
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.LAVA_CAULDRON)) {
            return true;
        }

        // Freezes, and drops you inside where you cannot climb out.
        if (state.is(Blocks.POWDER_SNOW) || state.is(Blocks.POWDER_SNOW_CAULDRON)) {
            return true;
        }

        // Cuts or stings on contact.
        if (state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POINTED_DRIPSTONE)) {
            return true;
        }

        // Moves the player to another dimension, which is never what a teleport here meant.
        if (state.is(Blocks.END_PORTAL)
                || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_GATEWAY)) {
            return true;
        }

        return false;
    }
}

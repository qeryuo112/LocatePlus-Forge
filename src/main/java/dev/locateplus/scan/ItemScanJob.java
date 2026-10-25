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
package dev.locateplus.scan;

import dev.locateplus.compat.Compat;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.ScanJob;
import dev.locateplus.model.ItemHit;
import dev.locateplus.model.ItemResult;
import dev.locateplus.report.Msg;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * Finds every copy of an item within range, wherever it is being kept.
 *
 * Each scope can be switched off in the config, and each is skipped entirely rather than filtered
 * afterwards, so turning one off makes the scan cheaper as well as quieter.
 */
public final class ItemScanJob implements ScanJob {

    private final ServerLevel world;
    private final ScanRegion region;
    private final ChunkAccessPolicy access;
    private final ItemMatcher matcher;
    private final ItemResult result;
    private final int blockRadius;
    private final Vec3 origin;
    private final Consumer<ItemResult> onComplete;
    private final Consumer<Throwable> onError;

    private final List<ChunkPos> chunks;
    private int chunkIndex;
    private boolean entitiesDone;

    private final long startNanos = System.nanoTime();

    /** Read once per scan. A nested container can recurse several levels for every stack. */
    private final int nestingDepth = LPConfig.get().itemNestingDepth();
    private boolean finished;

    public ItemScanJob(ServerLevel world, ScanRegion region, ItemMatcher matcher,
                       ItemResult result, int blockRadius, Vec3 origin,
                       Consumer<ItemResult> onComplete, Consumer<Throwable> onError) {
        this.world = world;
        this.region = region;
        this.access = new ChunkAccessPolicy(world, region.forceload());
        this.matcher = matcher;
        this.result = result;
        this.blockRadius = blockRadius;
        this.origin = origin;
        this.onComplete = onComplete;
        this.onError = onError;
        this.chunks = region.chunks();
    }

    @Override
    public String describe() {
        return "item scan (" + matcher.label() + ", " + region.totalChunks() + " chunks)";
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (finished) {
            return true;
        }
        try {
            LPConfig config = LPConfig.get();

            // Phase 1: container blocks, chunk by chunk.
            while (chunkIndex < chunks.size()) {
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                ChunkPos pos = chunks.get(chunkIndex++);
                LevelChunk chunk = access.acquire(pos);
                if (chunk == null) {
                    continue;
                }
                result.addChunkScanned();
                if (config.searchContainers()) {
                    scanContainers(chunk, config);
                }
            }

            // Phase 2: everything that moves.
            if (!entitiesDone) {
                entitiesDone = true;
                scanEntities(config);
            }

            complete();
            return true;
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    // ---- containers ---------------------------------------------------------------------------

    private void scanContainers(LevelChunk chunk, LPConfig config) {
        // Read once. A chunk can hold hundreds of block entities and these never change mid-scan.
        final int bandMinY = config.scanMinY(world);
        final int bandMaxY = config.scanMaxY(world);
        final boolean wantNested = config.searchInsideShulkerBoxes();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof Container inv)) {
                continue;
            }
            BlockPos pos = be.getBlockPos();
            if (outOfRange(pos.getX() + 0.5, pos.getZ() + 0.5)) {
                continue;
            }
            // Containers come from the chunk's block entity map rather than from a search box,
            // so the Y band has to be applied to them directly.
            if (pos.getY() < bandMinY || pos.getY() > bandMaxY) {
                continue;
            }
            String holder = containerName(be);
            double distance = distanceTo(pos);
            int size = inv.getContainerSize();

            for (int slot = 0; slot < size; slot++) {
                ItemStack stack;
                try {
                    stack = inv.getItem(slot);
                } catch (Throwable t) {
                    continue; // a modded inventory with an odd slot count must not abort the scan
                }
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (matcher.matches(stack)) {
                    record(stack, pos, distance, ItemHit.Channel.CONTAINER, holder);
                }
                if (wantNested) {
                    scanNested(stack, pos, distance, holder, 1);
                }
            }
        }
    }

    /** Read the contents of a container item, a shulker box being the obvious case. */
    private void scanNested(ItemStack container, BlockPos pos, double distance,
                            String outerHolder, int depth) {
        if (depth > nestingDepth) {
            return;
        }
        java.util.List<ItemStack> inside = Compat.nestedItems(container);
        if (inside.isEmpty()) {
            return;
        }

        String containerName = Msg.words(BuiltInRegistries.ITEM.getKey(container.getItem()).getPath());
        String holder = containerName + " in " + outerHolder;

        for (ItemStack inner : inside) {
            if (matcher.matches(inner)) {
                record(inner, pos, distance, ItemHit.Channel.NESTED, holder);
            }
            scanNested(inner, pos, distance, holder, depth + 1);
        }
    }

    // ---- entities -----------------------------------------------------------------------------

    private void scanEntities(LPConfig config) {
        boolean wantDropped = config.searchDroppedItems();
        boolean wantEntities = config.searchEntityInventories();
        boolean wantPlayers = config.searchPlayerInventories();

        if (wantDropped || wantEntities) {
            AABB box = searchBox();
            List<Entity> found = world.getEntities((Entity) null, box, e -> true);
            for (Entity entity : found) {
                if (outOfRange(entity.getX(), entity.getZ())) {
                    continue;
                }
                if (BlockBeacon.isMarker(entity)) {
                    continue; // this mod's own block marker holds no items
                }
                if (entity instanceof ItemEntity item) {
                    if (wantDropped) {
                        considerDropped(item);
                    }
                } else if (entity instanceof ServerPlayer) {
                    // Players are handled below, where the whole inventory is read at once.
                    // Equipment is part of that inventory, so counting it here as well would
                    // report a held stack twice and inflate the total.
                    continue;
                } else if (wantEntities) {
                    considerEntityInventory(entity, config);
                }
            }
        }

        if (wantPlayers) {
            for (ServerPlayer player : world.players()) {
                if (outOfRange(player.getX(), player.getZ())) {
                    continue;
                }
                considerPlayer(player, config);
            }
        }
    }

    private void considerDropped(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (!matcher.matches(stack)) {
            return;
        }
        BlockPos pos = item.blockPosition();
        record(stack, pos, distanceTo(item.position()), ItemHit.Channel.DROPPED, "the ground");
    }

    /** Equipment on any entity, plus anything an entity type carries in its own inventory. */
    private void considerEntityInventory(Entity entity, LPConfig config) {
        BlockPos pos = entity.blockPosition();
        double distance = distanceTo(entity.position());
        String holder = entityName(entity);

        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack;
                try {
                    stack = living.getItemBySlot(slot);
                } catch (Throwable t) {
                    continue;
                }
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (matcher.matches(stack)) {
                    record(stack, pos, distance, ItemHit.Channel.ENTITY,
                            holder + " (" + slot.getName() + ")");
                }
                if (config.searchInsideShulkerBoxes()) {
                    scanNested(stack, pos, distance, holder, 1);
                }
            }
        }

        if (entity instanceof ItemFrame frame) {
            ItemStack stack = frame.getItem();
            if (matcher.matches(stack)) {
                record(stack, pos, distance, ItemHit.Channel.ENTITY, "item frame");
            }
        }

        // An entity that carries an inventory is a container that happens to move: a chest
        // minecart, a hopper minecart, a chest boat. Its contents are in it, not on it, so it is
        // counted with the chests rather than with worn and held gear.
        if (entity instanceof Container inv && !(entity instanceof ServerPlayer)) {
            scanInventory(inv, pos, distance, holder, ItemHit.Channel.CONTAINER, config);
        }
    }

    private void considerPlayer(ServerPlayer player, LPConfig config) {
        BlockPos pos = player.blockPosition();
        double distance = distanceTo(player.position());
        String name = player.getGameProfile().getName();

        Inventory inv = player.getInventory();
        scanInventory(inv, pos, distance, name, ItemHit.Channel.PLAYER, config);

        try {
            scanInventory(player.getEnderChestInventory(), pos, distance,
                    name + " (ender chest)", ItemHit.Channel.PLAYER, config);
        } catch (Throwable ignored) {
            // the ender chest is optional detail, never worth failing a scan over
        }
    }

    private void scanInventory(Container inv, BlockPos pos, double distance, String holder,
                               ItemHit.Channel source, LPConfig config) {
        int size = inv.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack;
            try {
                stack = inv.getItem(slot);
            } catch (Throwable t) {
                continue;
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (matcher.matches(stack)) {
                record(stack, pos, distance, source, holder);
            }
            if (config.searchInsideShulkerBoxes()) {
                scanNested(stack, pos, distance, holder, 1);
            }
        }
    }

    // ---- shared -------------------------------------------------------------------------------

    private void record(ItemStack stack, BlockPos pos, double distance,
                        ItemHit.Channel source, String holder) {
        String customName = Compat.hasCustomName(stack) ? stack.getHoverName().getString() : null;
        boolean enchanted;
        try {
            enchanted = stack.isEnchanted();
        } catch (Throwable t) {
            enchanted = false;
        }
        result.add(ItemHit.of(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(),
                pos.immutable(), distance, source, holder, customName, enchanted));
    }

    /**
     * Whether a position lies outside what this scan covers.
     *
     * This matters most for players, who come from {@code world.players()} rather than from the
     * chunk list or the search box, and so have nothing else constraining them.
     */
    private boolean outOfRange(double x, double z) {
        if (blockRadius > 0) {
            double dx = origin.x - x;
            double dz = origin.z - z;
            return dx * dx + dz * dz > (double) blockRadius * blockRadius;
        }
        return !region.containsPosition(x, z);
    }

    private double distanceTo(BlockPos pos) {
        return Math.sqrt(origin.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private double distanceTo(Vec3 pos) {
        return Math.sqrt(origin.distanceToSqr(pos));
    }

    /** A coarse box used only to gather candidate entities cheaply. */
    private AABB searchBox() {
        double r = blockRadius > 0 ? blockRadius : region.blockExtent() + 16;
        return new AABB(
                origin.x - r, dev.locateplus.core.LPConfig.get().scanMinY(world), origin.z - r,
                origin.x + r, dev.locateplus.core.LPConfig.get().scanMaxY(world) + 1.0,
                origin.z + r);
    }

    /** A container's custom name if it has one, otherwise its block id without the namespace. */
    private static String containerName(BlockEntity be) {
        if (be instanceof BaseContainerBlockEntity lockable) {
            try {
                if (lockable.getCustomName() != null) {
                    return lockable.getCustomName().getString();
                }
            } catch (Throwable ignored) {
                // fall through to the block id
            }
        }
        try {
            return Msg.words(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath());
        } catch (Throwable t) {
            return "container";
        }
    }

    private static String entityName(Entity entity) {
        if (entity.getCustomName() != null) {
            return entity.getCustomName().getString();
        }
        if (entity instanceof ArmorStand) {
            return "armour stand";
        }
        return Msg.words(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
    }


    private void complete() {
        if (finished) {
            return;
        }
        finished = true;
        result.addChunksSkipped(access.skippedUnloaded());
        result.finish((System.nanoTime() - startNanos) / 1_000_000L);
        access.close();
        try {
            onComplete.accept(result);
        } catch (Throwable t) {
            LPLog.error("Item scan completion handler failed", t);
        }
    }

    private void fail(Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        access.close();
        LPLog.error("Item scan failed", t);
        try {
            onError.accept(t);
        } catch (Throwable ignored) {
            // reporting must not throw
        }
    }

    @Override
    public void onCancelled(Throwable cause) {
        if (!finished) {
            finished = true;
            access.close();
        }
    }
}

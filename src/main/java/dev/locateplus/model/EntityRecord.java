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
package dev.locateplus.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Immutable snapshot of one entity. */
public final class EntityRecord {

    private final ResourceLocation typeId;
    private final UUID uuid;
    private final String displayName;
    private final String customName;
    private final Vec3 pos;
    private final BlockPos blockPos;
    private final double distance;
    private final boolean glowing;
    private final Float health;
    private final Float maxHealth;
    private final String itemId;
    private final Integer itemCount;

    private EntityRecord(Builder b) {
        this.typeId = b.typeId;
        this.uuid = b.uuid;
        this.displayName = b.displayName;
        this.customName = b.customName;
        this.pos = b.pos;
        this.blockPos = b.blockPos;
        this.distance = b.distance;
        this.glowing = b.glowing;
        this.health = b.health;
        this.maxHealth = b.maxHealth;
        this.itemId = b.itemId;
        this.itemCount = b.itemCount;
    }

    /** Capture everything exports and {@code /lp inspect} need to show. */
    public static EntityRecord of(Entity entity, Vec3 origin) {
        Builder b = new Builder();
        b.typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        b.uuid = entity.getUUID();
        b.displayName = entity.getName().getString();
        b.customName = entity.getCustomName() == null ? null : entity.getCustomName().getString();
        b.pos = entity.position();
        b.blockPos = entity.blockPosition();
        b.distance = origin == null ? 0.0 : Math.sqrt(origin.distanceToSqr(entity.position()));
        b.glowing = entity.isCurrentlyGlowing();

        if (entity instanceof LivingEntity living) {
            b.health = living.getHealth();
            b.maxHealth = living.getMaxHealth();
        }
        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            b.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            b.itemCount = stack.getCount();
        }
        return new EntityRecord(b);
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    public UUID uuid() {
        return uuid;
    }

    public String displayName() {
        return displayName;
    }

    public String customName() {
        return customName;
    }

    public Vec3 pos() {
        return pos;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    public double distance() {
        return distance;
    }

    public boolean glowing() {
        return glowing;
    }

    public Float health() {
        return health;
    }

    public Float maxHealth() {
        return maxHealth;
    }

    public String itemId() {
        return itemId;
    }

    public Integer itemCount() {
        return itemCount;
    }

    private static final class Builder {
        ResourceLocation typeId;
        UUID uuid;
        String displayName;
        String customName;
        Vec3 pos;
        BlockPos blockPos;
        double distance;
        boolean glowing;
        Float health;
        Float maxHealth;
        String itemId;
        Integer itemCount;
    }
}

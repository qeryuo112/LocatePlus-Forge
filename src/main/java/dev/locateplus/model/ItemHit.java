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
import net.minecraft.resources.ResourceLocation;

/** One place a searched-for item was found, and how it was being held. */
public final class ItemHit {

    /** What was holding the item. Determines how the hit is described and grouped. */
    public enum Channel {

        /** A block with an inventory: chest, barrel, hopper, furnace, modded machine. */
        CONTAINER("container", "in containers"),
        /** A shulker box or similar sitting inside another container. */
        NESTED("nested", "in shulker boxes"),
        /** An item entity lying on the ground. */
        DROPPED("dropped", "on the ground"),
        /** Held, worn, or inside a mob. Also item frames and armour stands. */
        ENTITY("entity", "on mobs"),
        /** A player's inventory or ender chest. */
        PLAYER("player", "on players");

        private final String label;
        private final String phrase;

        Channel(String label, String phrase) {
            this.label = label;
            this.phrase = phrase;
        }

        /** One word, for the fixed-width columns of an export. */
        public String label() {
            return label;
        }

        /** How a player would say it, for chat. */
        public String phrase() {
            return phrase;
        }
    }

    private final ResourceLocation itemId;
    private final int count;
    private final BlockPos pos;
    private final double distance;
    private final Channel source;
    private final String holder;
    private final String customName;
    private final boolean enchanted;

    private ItemHit(ResourceLocation itemId, int count, BlockPos pos, double distance, Channel source,
                    String holder, String customName, boolean enchanted) {
        this.itemId = itemId;
        this.count = count;
        this.pos = pos;
        this.distance = distance;
        this.source = source;
        this.holder = holder;
        this.customName = customName;
        this.enchanted = enchanted;
    }

    public static ItemHit of(ResourceLocation itemId, int count, BlockPos pos, double distance,
                             Channel source, String holder, String customName, boolean enchanted) {
        return new ItemHit(itemId, count, pos, distance, source, holder, customName, enchanted);
    }

    public ResourceLocation itemId() {
        return itemId;
    }

    public int count() {
        return count;
    }

    public BlockPos pos() {
        return pos;
    }

    public double distance() {
        return distance;
    }

    public Channel source() {
        return source;
    }

    /**
     * What was holding it, in words: {@code "chest"}, {@code "zombie"}, {@code "Steve"}, {@code
     * "shulker_box in chest"}. Never null, may be empty for a dropped item.
     */
    public String holder() {
        return holder;
    }

    /** The item's custom name, or null. */
    public String customName() {
        return customName;
    }

    public boolean enchanted() {
        return enchanted;
    }
}

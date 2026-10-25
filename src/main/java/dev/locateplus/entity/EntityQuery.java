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
package dev.locateplus.entity;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Locale;
import java.util.function.Predicate;

/** A resolved, radius-independent description of "which entities the player meant". */
public final class EntityQuery {

    private final String displayName;
    private final Predicate<Entity> predicate;
    private final boolean playersOnly;

    private EntityQuery(String displayName, Predicate<Entity> predicate, boolean playersOnly) {
        this.displayName = displayName;
        this.predicate = predicate;
        this.playersOnly = playersOnly;
    }

    public String displayName() {
        return displayName;
    }

    /** True when only players can possibly match ({@code @a}, {@code @p}, {@code @r}, a name). */
    public boolean playersOnly() {
        return playersOnly;
    }

    public boolean matches(Entity entity) {
        try {
            return predicate.test(entity);
        } catch (Throwable t) {
            // A modded entity with a broken getName()/getType() must never abort a whole scan.
            return false;
        }
    }

    // ---- factories ----------------------------------------------------------------------------

    public static EntityQuery all() {
        return new EntityQuery("any entity", e -> true, false);
    }

    public static EntityQuery ofType(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        boolean players = type == EntityType.PLAYER;
        return new EntityQuery(id.toString(), e -> e.getType() == type, players);
    }

    public static EntityQuery ofTag(TagKey<EntityType<?>> tag) {
        return new EntityQuery("#" + tag.location(), e -> e.getType().is(tag), false);
    }

    /** Matches a player by exact name, case-insensitively. */
    public static EntityQuery ofPlayerName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        return new EntityQuery(name,
                e -> e instanceof net.minecraft.server.level.ServerPlayer
                        && e.getName().getString().toLowerCase(Locale.ROOT).equals(wanted),
                true);
    }

    /** Matches one specific entity instance, used for UUID lookups and {@code @s}. */
    public static EntityQuery ofExact(Entity target, String label) {
        java.util.UUID uuid = target.getUUID();
        return new EntityQuery(label, e -> e.getUUID().equals(uuid),
                target instanceof net.minecraft.server.level.ServerPlayer);
    }

    public static EntityQuery ofPredicate(String label, Predicate<Entity> predicate, boolean playersOnly) {
        return new EntityQuery(label, predicate, playersOnly);
    }

    /** Combines this query with an extra condition, keeping the original label. */
    public EntityQuery and(Predicate<Entity> extra) {
        Predicate<Entity> base = this.predicate;
        return new EntityQuery(displayName, e -> base.test(e) && extra.test(e), playersOnly);
    }

    /**
     * Excludes the command source's own entity. Used so {@code /locate entity @e[type=player]} does
     * not simply report the caller at 0 blocks.
     */
    public EntityQuery excludingSelf(CommandSourceStack source) {
        Entity self = source.getEntity();
        if (self == null) {
            return this;
        }
        java.util.UUID selfId = self.getUUID();
        return and(e -> !e.getUUID().equals(selfId));
    }
}

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

import dev.locateplus.compat.Compat;
import dev.locateplus.report.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Entity {@code /lp inspect} sections: equipment, status effects, villager trades, and pet ownership.
 *
 * Each section is skipped entirely when it has nothing to report, so inspecting a plain zombie
 * stays short while inspecting a geared, named, effect-laden one shows everything.
 */
public final class EntityInspector {

    private EntityInspector() {
    }

    /** Everything worth knowing about one entity, appended to {@code out}. */
    public static void describe(InspectLine out, Entity entity, ServerLevel world) {
        equipment(out, entity);
        effects(out, entity);
        villager(out, entity);
        tameable(out, entity, world);
        misc(out, entity);
    }

    // ---- equipment ----------------------------------------------------------------------------

    private static void equipment(InspectLine out, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        InspectLine section = new InspectLine();

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

            StringBuilder line = new StringBuilder();
            line.append(slot.getName()).append(": ");
            if (stack.getCount() > 1) {
                line.append(stack.getCount()).append("x ");
            }
            line.append(BuiltInRegistries.ITEM.getKey(stack.getItem()));

            if (Compat.hasCustomName(stack)) {
                line.append(" \"").append(stack.getHoverName().getString()).append('"');
            }
            if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                line.append("  [").append(remaining).append('/')
                        .append(stack.getMaxDamage()).append(" durability]");
            }

            boolean enchanted = Compat.hasEnchantments(stack);
            section.item(line.toString(), enchanted ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.WHITE);

            if (enchanted) {
                for (String line2 : Compat.enchantmentLines(stack)) {
                    section.item("    " + line2, ChatFormatting.DARK_PURPLE);
                }
            }
        }

        if (!section.isEmpty()) {
            out.section("Equipment");
            out.lines().addAll(section.lines());
        }
    }

    // ---- status effects -----------------------------------------------------------------------

    private static void effects(InspectLine out, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        Collection<MobEffectInstance> active;
        try {
            active = living.getActiveEffects();
        } catch (Throwable t) {
            return;
        }
        if (active.isEmpty()) {
            return;
        }

        out.section("Status effects");
        for (MobEffectInstance instance : active) {
            var id = Compat.statusEffectId(instance);
            String name = id == null ? instance.getEffect().toString() : id.toString();
            int level = instance.getAmplifier() + 1;

            String duration = instance.isInfiniteDuration()
                    ? "infinite"
                    : formatTicks(instance.getDuration());

            StringBuilder line = new StringBuilder();
            line.append(name);
            if (level > 1) {
                line.append(" ").append(level);
            }
            line.append("  (").append(duration).append(')');
            if (instance.isAmbient()) {
                line.append(" [ambient]");
            }

            out.item(line.toString(), Compat.statusEffectBeneficial(instance)
                    ? ChatFormatting.GREEN : ChatFormatting.RED);
        }
    }

    private static String formatTicks(int ticks) {
        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0) {
            return String.format(Locale.US, "%d:%02d", minutes, seconds);
        }
        return seconds + "s";
    }

    // ---- villagers ----------------------------------------------------------------------------

    private static void villager(InspectLine out, Entity entity) {
        if (!(entity instanceof Villager villager)) {
            return;
        }
        out.section("Villager");

        VillagerData data = villager.getVillagerData();
        var professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession());
        var typeId = BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType());

        out.field("Profession", professionId == null ? "unknown" : professionId.toString());
        out.field("Biome type", typeId == null ? "unknown" : Msg.words(typeId.getPath()));
        out.field("Level", data.getLevel() + " (" + careerTier(data.getLevel()) + ")");
        out.field("Experience", String.valueOf(villager.getVillagerXp()));

        try {
            int offers = villager.getOffers().size();
            out.field("Trades offered", String.valueOf(offers));

            int used = 0;
            for (var offer : villager.getOffers()) {
                if (offer.isOutOfStock()) {
                    used++;
                }
            }
            if (used > 0) {
                out.field("Locked trades", used + " of " + offers, ChatFormatting.YELLOW);
            }
        } catch (Throwable ignored) {
            // offers may not be generated until first interaction
        }
    }

    /** Vanilla career tier names for villager levels 1-5. */
    private static String careerTier(int level) {
        return switch (level) {
            case 1 -> "Novice";
            case 2 -> "Apprentice";
            case 3 -> "Journeyman";
            case 4 -> "Expert";
            case 5 -> "Master";
            default -> "level " + level;
        };
    }

    // ---- pets ---------------------------------------------------------------------------------

    private static void tameable(InspectLine out, Entity entity, ServerLevel world) {
        InspectLine section = new InspectLine();
        UUID owner = null;

        if (entity instanceof TamableAnimal tameable) {
            section.field("Tamed", String.valueOf(tameable.isTame()),
                    tameable.isTame() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            section.field("Sitting", String.valueOf(tameable.isInSittingPose()));
            owner = tameable.getOwnerUUID();
        } else if (entity instanceof AbstractHorse horse) {
            section.field("Tamed", String.valueOf(horse.isTamed()),
                    horse.isTamed() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            section.field("Saddled", String.valueOf(horse.isSaddled()));
            owner = horse.getOwnerUUID();
        }

        if (owner != null) {
            Player ownerPlayer = world.getPlayerByUUID(owner);
            section.field("Owner", owner + (ownerPlayer == null
                    ? "  (offline)" : "  (" + ownerPlayer.getName().getString() + ")"));
        }

        // Breeding state applies to any animal, tame or not.
        if (entity instanceof Animal animal) {
            if (animal.isBaby()) {
                section.field("Age", "baby", ChatFormatting.YELLOW);
            }
            int loveTicks = animal.getInLoveTime();
            if (loveTicks > 0) {
                section.field("In love", formatTicks(loveTicks), ChatFormatting.LIGHT_PURPLE);
            }
            int breedingAge = animal.getAge();
            if (breedingAge > 0) {
                section.field("Breeding cooldown", formatTicks(breedingAge));
            }
        }

        if (!section.isEmpty()) {
            out.section("Animal / pet");
            out.lines().addAll(section.lines());
        }
    }

    // ---- misc ---------------------------------------------------------------------------------

    private static void misc(InspectLine out, Entity entity) {
        InspectLine section = new InspectLine();

        Set<String> tags = entity.getTags();
        if (!tags.isEmpty()) {
            section.field("Command tags", String.join(", ", tags), ChatFormatting.AQUA);
        }
        if (entity.isSilent()) {
            section.field("Silent", "true");
        }
        if (entity.isInvulnerable()) {
            section.field("Invulnerable", "true", ChatFormatting.YELLOW);
        }
        if (entity.hasCustomName() && entity.isCustomNameVisible()) {
            section.field("Nameplate", "visible");
        }
        if (entity instanceof Mob mob) {
            if (mob.isPersistenceRequired()) {
                section.field("Persistent", "true (will not despawn)", ChatFormatting.GREEN);
            }
            if (mob.isNoAi()) {
                section.field("AI", "disabled", ChatFormatting.YELLOW);
            }
            LivingEntity target = mob.getTarget();
            if (target != null) {
                section.field("Attacking", target.getName().getString(), ChatFormatting.RED);
            }
            if (mob.isLeashed()) {
                section.field("Leashed", "true");
            }
        }
        if (entity instanceof LivingEntity living) {
            if (living.isSleeping()) {
                section.field("Sleeping", "true");
            }
            if (living.getArmorValue() > 0) {
                section.field("Armor points", String.valueOf(living.getArmorValue()));
            }
            if (living.isOnFire()) {
                section.field("On fire", "true", ChatFormatting.RED);
            }
        }

        if (!section.isEmpty()) {
            out.section("Other");
            out.lines().addAll(section.lines());
        }
    }
}

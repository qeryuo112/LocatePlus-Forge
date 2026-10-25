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
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.locateplus.core.LPConfig;
import dev.locateplus.report.Chat;
import dev.locateplus.teleport.SafeSpot;
import dev.locateplus.teleport.TeleportService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /safetp [targets] <destination>}.
 *
 * Note the argument order: Brigadier resolves the one-argument form first, so {@code /safetp 120 64
 * -35} is unambiguous and {@code /safetp @a 120 64 -35} only matches when a selector is actually
 * present.
 */
public final class SafeTpCommand {

    /**
     * Offer {@code ~ ~ ~} and nothing else for a coordinate.
     *
     * Left to itself the client suggests two things at once: the relative form, and the numeric
     * coordinates of whatever block is under the crosshair. Two candidates that look nothing alike
     * in the same slot make the argument hard to read, and the numeric one is rarely what is
     * wanted, so only the relative form is offered.
     */
    private static final SuggestionProvider<CommandSourceStack> RELATIVE_ONLY =
            (ctx, builder) -> SharedSuggestionProvider.suggestCoordinates(builder.getRemaining(),
                    java.util.List.of(SharedSuggestionProvider.TextCoordinates.DEFAULT_LOCAL),
                    builder, text -> true);

    private SafeTpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("safetp")
                .requires(source -> source.hasPermission(LPConfig.get().permissionLevel()))

                // /safetp <destination>
                .then(argument("destination", Vec3Argument.vec3())
                        .suggests(RELATIVE_ONLY)
                        .executes(ctx -> teleportSelf(ctx)))

                // /safetp <targets> <destination>
                .then(argument("targets", EntityArgument.entities())
                        .then(argument("destination", Vec3Argument.vec3())
                                .suggests(RELATIVE_ONLY)
                                .executes(ctx -> teleportTargets(ctx)))));
    }

    private static int teleportSelf(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Entity self = source.getEntity();
        if (self == null) {
            Chat.error(source, "This form of /safetp needs an entity to move. "
                    + "From the console, use /safetp <targets> <destination>.");
            return 0;
        }
        return run(source, Collections.singletonList(self), destination(ctx));
    }

    private static int teleportTargets(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Collection<? extends Entity> targets =
                EntityArgument.getOptionalEntities(ctx, "targets");
        if (targets.isEmpty()) {
            Chat.warn(source, "No entities matched that selector. Nothing was teleported.");
            return 0;
        }
        return run(source, List.copyOf(targets), destination(ctx));
    }

    private static Vec3 destination(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        // Handles absolute, ~ relative and ^ local coordinates exactly as vanilla /tp does.
        return Vec3Argument.getVec3(ctx, "destination");
    }

    private static int run(CommandSourceStack source, List<? extends Entity> targets,
                           Vec3 destination) {
        ServerLevel world = source.getLevel();
        BlockPos requested = BlockPos.containing(destination);

        // Same test vanilla /tp applies. Outside it the world cannot generate terrain, so the
        // search would read nothing and the chunks it asked for would fail on the server thread.
        if (!world.isInWorldBounds(requested)) {
            Chat.error(source, "That position is outside the world. "
                    + "X and Z go up to 30,000,000 and Y from -20,000,000 to 20,000,000.");
            return 0;
        }

        int moved = 0;
        int failed = 0;
        int unchecked = 0;

        for (Entity entity : targets) {
            SafeSpot spot = TeleportService.teleportSafely(entity, world, destination);
            if (spot == null) {
                failed++;
                continue;
            }
            moved++;
            if (!spot.isChecked()) {
                unchecked++;
            }

            // Tell the player who moved where they ended up.
            if (entity instanceof ServerPlayer player) {
                String message = spot.isChecked()
                        ? "Teleported to " + spot.describeLanding()
                        : "Teleported to " + Chat.coords(spot.blockPos())
                                + ". Nowhere safe to stand was found, so this is the exact spot "
                                + "you asked for and it was not checked.";
                player.displayClientMessage(Chat.prefixed(Component.literal(message)
                        .withStyle(spot.isChecked() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)),
                        false);
            }

            // And tell the command source, when it is someone else.
            if (source.getEntity() != entity) {
                String name = entity.getName().getString();
                if (spot.isChecked()) {
                    Chat.success(source, name + " -> " + spot.describeLanding());
                } else {
                    Chat.warn(source, name + " -> " + Chat.coords(spot.blockPos())
                            + ", unchecked.");
                }
            }
        }

        if (unchecked > 0 && source.getEntity() == null) {
            Chat.warn(source, "No safe location found near " + Chat.coords(requested)
                    + ", so " + unchecked + " target" + (unchecked == 1 ? " was" : "s were")
                    + " sent there unchecked.");
        }
        if (failed > 0) {
            Chat.error(source, "No safe location found near " + Chat.coords(requested)
                    + " for " + failed + " target" + (failed == 1 ? "" : "s")
                    + ". Set fallback_to_vanilla_tp to true to teleport there anyway.");
        }
        return moved;
    }
}

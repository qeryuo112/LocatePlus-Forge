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
package dev.locateplus.forge;

import dev.locateplus.command.LPCommands;
import dev.locateplus.core.ConfigFile;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.core.TickTasks;
import dev.locateplus.platform.Services;
import dev.locateplus.scan.BlockClass;
import dev.locateplus.scan.Highlights;
import dev.locateplus.visual.BlockBeacon;
import dev.locateplus.visual.RegionOutline;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entrypoint.
 *
 * Server-side: every command is registered into the server's command tree and every scan reads
 * the server's copy of the world, so the mod does the same work in singleplayer, where the
 * integrated server runs this code too. No client classes are touched, so a client without the
 * mod can still join a server that has it.
 */
@Mod(LPConstants.MOD_ID)
public final class LocatePlusForge {

    /** How long after startup chunk loads are checked for markers left by a previous run. */
    private static final long STALE_MARKER_SWEEP_MILLIS = 5 * 60 * 1000L;

    /** When to stop checking. Set on server start. */
    private static volatile long markerSweepDeadline;

    /** How often untracked markers are looked for, in ticks. Ten seconds. */
    private static final int ORPHAN_SWEEP_TICKS = 200;

    private static int tickCounter;

    public LocatePlusForge() {
        if (Services.isBootstrapped()) {
            return;
        }
        Services.bootstrap(new ForgePlatform());

        // Read the config before anything else: which commands exist and what permission level they
        // need are both decided while the command tree is being built.
        LPLog.info(ConfigFile.load());

        MinecraftForge.EVENT_BUS.register(this);

        LPLog.info(LPConstants.MOD_NAME + " ready. Type /lp in game for the command list "
                + "(permission level " + LPConfig.get().permissionLevel() + ", server-side)");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LPCommands.registerAll(event.getDispatcher(), event.getBuildContext());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LPScheduler.onServerTick();
        TickTasks.tick();
        Highlights.tick(event.getServer());

        // A marker is only supposed to exist while something is tracking it. Checking now and
        // then costs one entity query per world and catches anything that came adrift, so a
        // stray outline cannot outlive the run that made it.
        if (++tickCounter % ORPHAN_SWEEP_TICKS == 0) {
            int stray = Highlights.sweepOrphans(event.getServer());
            if (stray > 0) {
                LPLog.info("Removed " + stray + " block marker(s) that nothing was tracking");
            }
        }
    }

    // Markers are real entities, so a server that stops without running its shutdown hook
    // leaves them in the world.
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        markerSweepDeadline = System.currentTimeMillis() + STALE_MARKER_SWEEP_MILLIS;
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (System.currentTimeMillis() > markerSweepDeadline) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity.getTags().contains(BlockBeacon.TAG) && !BlockBeacon.isOurs(entity.getUUID())) {
            entity.discard();
            LPLog.info("Removed a block marker left by a previous run");
        }
    }

    // A glow flag on anything that is not a living entity is saved with the entity, so it has
    // to come off before the entity is written out rather than when its timer happens to run.
    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        Highlights.releaseGlow(event.getEntity());
    }

    // Block tags are rebuilt when a datapack reloads, so every cached answer about which blocks
    // count as ore or as ground has to be thrown away with them. Forge has no "reload finished"
    // event, so the cache is dropped by a listener that runs as part of the reload itself.
    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resources, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resources, ProfilerFiller profiler) {
                BlockClass.invalidate();
            }
        });
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LPScheduler.shutdown();
        TickTasks.clear();
        RegionOutline.clear();
        BlockBeacon.sweep(event.getServer());
        Highlights.releaseAllGlow(event.getServer());
        Highlights.forgetBeacons();
    }
}

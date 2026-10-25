package io.github.vexedpanda.mcincremental.prestige.run;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * A Run stays in its Run dimension, which only changes at Prestige (pack ADR-0008): during a Run, lighting a Nether
 * portal frame fails and no portal (Nether, End, End gateway) moves anything. Before the world's first Run, portals
 * work as usual. Server thread only.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class RunDimensionLock {
    /** Action-bar message for a player whose portal trip was stopped. */
    public static final String BLOCKED_KEY = McIncrementalPrestige.MODID + ".portal.blocked";

    private RunDimensionLock() {}

    /** Fire placed in an obsidian frame (flint and steel, fire charge, dispenser, lava, lightning) makes no portal. */
    @SubscribeEvent
    static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        LevelAccessor level = event.getLevel();
        if (level.getServer() != null && duringRun(level.getServer())) event.setCanceled(true);
    }

    /**
     * Stops a portal trip. Every portal goes through {@code Entity.handlePortal}, which only runs for an entity with a
     * {@code portalProcess} (set while it stands in a portal block) and then calls {@code changeDimension}, which
     * posts this event, also for an End gateway's same-dimension jump. Other teleports, such as {@code /tp} or the
     * Prestige teleport ({@link RunAreas#teleport} drops any portal process first), have no portal process and pass.
     * The portal process is dropped too, so an entity still in the portal waits the full portal delay before it is
     * stopped again.
     */
    @SubscribeEvent
    static void onTravel(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        if (entity.portalProcess == null || !(entity.level() instanceof ServerLevel level)
                || !duringRun(level.getServer())) return;
        event.setCanceled(true);
        entity.portalProcess = null;
        if (entity instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable(BLOCKED_KEY), true);
        }
    }

    private static boolean duringRun(MinecraftServer server) {
        return RunAreas.current(server).isPresent();
    }
}

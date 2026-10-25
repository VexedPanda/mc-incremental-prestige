package io.github.vexedpanda.mcincremental.prestige.run;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Caps singleplayer's simulation distance to the Run area: {@link RunArea#simulationDistanceChunks()} of the current
 * area (2 for a 3x3 area), or of an area of the configured radius before the first Run. The integrated server takes
 * its simulation distance from the client option, whose slider can't go below 5, so without this singleplayer ticks
 * far more than the Run area. Dedicated servers keep {@code server.properties}' {@code simulation-distance}. Render
 * distance stays the player's choice.
 * <p>
 * Checked every server tick, not once: the integrated server re-applies the client option whenever the slider
 * changes. It does that after this handler in the same tick, so a slider change lasts one tick before the cap
 * is back. Gated on {@link MinecraftServer#isSingleplayer()} (the server has a singleplayer owner), not on
 * {@code !isDedicatedServer()}, which is also true of the GameTest server.
 */
@EventBusSubscriber(modid = McIncrementalPrestige.MODID)
public final class SingleplayerSimulationDistance {
    private SingleplayerSimulationDistance() {}

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!server.isSingleplayer()) return;
        int cap = RunAreas.current(server).map(RunArea::simulationDistanceChunks)
                .orElseGet(() -> 2 * RunAreaConfig.RADIUS_CHUNKS.get());
        PlayerList players = server.getPlayerList();
        // Only when above the cap: setting it broadcasts to every player and updates every level.
        if (players.getSimulationDistance() > cap) {
            players.setSimulationDistance(cap);
        }
    }
}
